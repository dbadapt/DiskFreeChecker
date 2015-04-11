import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.CodeSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;

/**
 * This class implements disk free volume checker service and reporter in a
 * single java source file.
 * 
 * Dependencies: - JRE 1.5 or better, MySQL JDBC Driver 5, POSIX df utility on system
 * 
 * @author david.bennett@percona.com
 * 
 */
public class DiskFreeChecker {

  /**
   * Are we in service mode?
   */
  private boolean serviceMode = false;

  // all of our JDBC calls are synchronous
  // we re-use these object properties
  // to allow graceful shutdown.
  // See: registerShutdownHook()
  //
  private Properties dbp = null;
  private Connection con = null;
  private Statement st = null;
  private PreparedStatement pst = null;
  private ResultSet rs = null;
  private Process procDf = null;

  /** 
   * load the properties file.   This code looks a bit daunting I know
   * but it makes it easy for us to bundle the configuration properties
   * along with the .class or .jar file.  As long as the properties is
   * in the same directory along with the .class or .jar then it should
   * get loaded. 
   * 
   * @return Properties
   */  
  Properties getProperties() {
    String propFile="DiskFreeChecker.properties";
    // read the service configuration properties if we haven't
    // already
    if (dbp == null || dbp.isEmpty()) {
      try {
        dbp = new Properties();
        // look for properties in directory with .class file
        InputStream isClass=DiskFreeChecker.class.getClassLoader()
            .getResourceAsStream(propFile);
        if (isClass != null) {
          dbp.load(isClass);
        } else {
          // look for properties in directory with .jar file
          CodeSource cs = DiskFreeChecker.class.getProtectionDomain()
              .getCodeSource();
          File jarFile = new File(cs.getLocation().toURI().getPath());
          File jarDir = jarFile.getParentFile();
          if (jarDir != null && jarDir.isDirectory()) {
            dbp.load(new BufferedReader((new FileReader(
                new File(jarDir,propFile)))));
          }
        }
      } catch (Exception e) {}
      if (dbp == null || dbp.isEmpty()) {
        System.err.println("Could not find properties along with class or jar");
        System.exit(1);
      }
    }
    return(dbp);
  }
  
  /**
   * Connect to the database, returns the open connection if already connected
   * 
   * @return Connection
   */
  private Connection getConnection() {
    // load our .properties file
    dbp=getProperties();
    // open the database connection if it isn't already open
    if (con == null) {
      // load the driver
      try {
        Class.forName(dbp.getProperty("driver.class"));
      } catch (ClassNotFoundException cnfe) {
        System.err.printf("Could not find JDBC Driver: %s\n",dbp.getProperty("driver.class"));
        System.err.printf(cnfe.getMessage());
        System.err.printf("Make sure to set the classpath with the -cp option or CLASSPATH environment variable\n");
        System.exit(1);
      }
      // open the connection
      try {
        con = DriverManager.getConnection(
            dbp.getProperty("connection.url"), dbp);
      } catch (SQLException sqle) {
        System.err.printf("Database connection: %s\n",
            dbp.getProperty("connection.url"));
        System.err.printf("%s\n",sqle.getMessage());
        System.err.printf("Check the connection URL,user and password\n");
        System.exit(1);
      }
    }
    // check for an error and exit if necessary
    try {
      if (con == null || !con.isValid(0)) {
        System.err.print("Database connection is invalid.");
        System.exit(1);
      }
    } catch (SQLException sqle) {
      sqle.printStackTrace(System.err);
      System.exit(1);
    }
    // return the open connection
    return (con);
  }

  /**
   * Close resources on exit.
   */
  void registerShutdownHook() {
    // close the database resources on shutdown
    Runtime.getRuntime().addShutdownHook(new Thread() {
      public void run() {
        if (serviceMode)
          System.out.println("Shutting down");
        try {
          if (procDf != null)
            procDf.destroy();
          if (rs != null)
            rs.close();
          if (st != null)
            st.close();
          if (pst != null)
            pst.close();
          if (con != null)
            con.close();
        } catch (SQLException sqle) {
        } // ignore
      }
    });
  }

  /**
   *  object representation of
   *  a Posix df utility data row
   *  
   */
  class DiskFreeEntry {
    Long id = null;
    String hostname = null;
    String fileSystemName = null;
    Long totalSpace = null;
    Long spaceUsed = null;
    Long spaceFree = null;
    BigDecimal percentageUsed = null;
    String fileSystemRoot = null;
    Date when = null;
  }

  /**
   * check to make sure we have our table
   * 
   * @return boolean true if our table exists
   */
  boolean checkTable() throws SQLException {
    con = getConnection();
    st = con.createStatement();
    rs = st.executeQuery("SHOW TABLES LIKE 'diskfree'");
    boolean haveIt = rs.next();
    rs.close();
    st.close();
    return (haveIt);
  }

  /**
   * Create the database table if it doesn't exist
   * 
   */
  void createTable() throws SQLException {
    // create table if it doesn't exist
    st = con.createStatement();
    st.execute("CREATE TABLE IF NOT EXISTS diskfree ("
        + "`id` BIGINT AUTO_INCREMENT PRIMARY KEY,"
        + "`hostname` VARCHAR(255)," + "`file_system_name` VARCHAR(255),"
        + "`total_space` BIGINT," + "`space_used` BIGINT,"
        + "`space_free` BIGINT," + "`percentage_used` DECIMAL(3,2),"
        + "`file_system_root` VARCHAR(255)," + "`when` DATETIME,"
        + "KEY `file_system_name_key` "
        + " (`file_system_name`,`hostname`,`when`,`id`),"
        + "KEY `file_system_root_key` "
        + " (`file_system_root`,`hostname`,`when`,`id`)," + "KEY `when_key` "
        + " (`when`,`hostname`,`id`))");
    st.close();
    st = null;
  }

  /**
   * Get 'df' entries. See
   * http://pubs.opengroup.org/onlinepubs/9699919799/utilities/df.html for
   * format
   */
  ArrayList<DiskFreeEntry> getDiskFreeEntries() throws IOException {
    // execute posix 'df' and parse into an array
    ArrayList<DiskFreeEntry> DiskFreeEntries = new ArrayList<DiskFreeEntry>();
    String strDfLine = null;
    procDf = Runtime.getRuntime().exec("df -kP");
    BufferedReader stdinDf = new BufferedReader(new InputStreamReader(
        procDf.getInputStream()));
    int line = 0;
    boolean good = true; // optimistic
    while ((strDfLine = stdinDf.readLine()) != null) {
      if (line++ == 0) {
        continue;
      } // skip 1st line
      ArrayList<String> aryDfRow = new ArrayList<String>(
          Arrays.asList(strDfLine.split(" +")));
      // Initialize our DiskFree Entry
      DiskFreeEntry diskFreeEntry = new DiskFreeEntry();
      diskFreeEntry.hostname = InetAddress.getLocalHost().getHostName();
      diskFreeEntry.fileSystemName = aryDfRow.get(0);
      diskFreeEntry.totalSpace = new Long(aryDfRow.get(1));
      diskFreeEntry.spaceUsed = new Long(aryDfRow.get(2));
      diskFreeEntry.spaceFree = new Long(aryDfRow.get(3));
      diskFreeEntry.percentageUsed = new BigDecimal(aryDfRow.get(4).replace(
          "%", "")).divide(new BigDecimal(100));
      // handle mount points with spaces
      if (aryDfRow.size() < 6) {
        good = false; // we're missing something unknown
      } else if (aryDfRow.size() == 6) {
        diskFreeEntry.fileSystemRoot = aryDfRow.get(5);
      } else { // (aryDfRow.size() > 6)
        ArrayList<String> aryMP = new ArrayList<String>(Arrays.asList(strDfLine
            .split("% /")));
        if (aryMP.size() == 2) {
          diskFreeEntry.fileSystemRoot = "/" + aryMP.get(1);
        } else {
          good = false; // something went wrong
        }
      }
      if (good) {
        DiskFreeEntries.add(diskFreeEntry);
      } else {
        System.err.println("Could not parse 'df' line:\n\t" + strDfLine);
      }
    }
    procDf.destroy();
    procDf = null;
    return (DiskFreeEntries);
  }

  /**
   * Run the service
   * 
   * @throws SQLException
   * @throws ClassNotFoundException
   * @throws IOException
   * 
   */
  public void service(String[] args) throws SQLException,
      ClassNotFoundException, IOException {

    serviceMode = true;

    // open database connection
    con = getConnection();

    // create table if we need to
    if (!checkTable())
      createTable();

    // sleep value for check.seconds (convert to milliseconds)
    String strSleep = dbp.getProperty("check.seconds");
    if (strSleep == null || "".equals(strSleep.trim()))
      strSleep = "60";
    long sleep = new Long(strSleep).longValue() * 1000;

    /**
     * Main service loop
     */
    while (true) {

      // get time in millis
      Timestamp when = new Timestamp(Calendar.getInstance().getTimeInMillis());

      pst = con.prepareStatement(                          // prepare insert
          "INSERT INTO diskfree (`hostname`,`file_system_name`,"
              + "`total_space`,`space_used`,`space_free`,`percentage_used`,"
              + "`file_system_root`,`when`) VALUES (?,?,?,?,?,?,?,?)");

      for (DiskFreeEntry diskFreeEntry : getDiskFreeEntries()) {  // iterate
        pst.setString    (1, diskFreeEntry.hostname);
        pst.setString    (2, diskFreeEntry.fileSystemName);
        pst.setLong      (3, diskFreeEntry.totalSpace);
        pst.setLong      (4, diskFreeEntry.spaceUsed);
        pst.setLong      (5, diskFreeEntry.spaceFree);
        pst.setBigDecimal(6, diskFreeEntry.percentageUsed);
        pst.setString    (7, diskFreeEntry.fileSystemRoot);
        pst.setTimestamp (8, when);
        pst.addBatch();                                      // add to batch
      }
      int[] inserts = pst.executeBatch();                   // execute batch
      int totalInserts = 0;
      for (int i : inserts)
        totalInserts += i;                                  // compute total
      System.out.println(when + " recorded " + totalInserts + " entries.");
      pst.close();

      // sleep until next loop
      try {
        Thread.sleep(sleep);
      } catch (InterruptedException ie) {
      }
    }
  }

  /**
   * load an array given hostname and time
   * 
   * @param hostname
   *          String
   * @param ts
   *          Timestamp
   */
  HashMap<String, DiskFreeEntry> queryDiskFree(String hostname, Timestamp ts)
      throws SQLException {
    HashMap<String, DiskFreeEntry> diskFreeEntries = 
        new HashMap<String, DiskFreeEntry>();
    pst = con.prepareStatement("SELECT * FROM diskfree "
        + "WHERE `hostname` = ? AND `when` = ?");
    pst.setString(1, hostname);
    pst.setTimestamp(2, ts);
    rs = pst.executeQuery();
    while (rs.next()) {
      DiskFreeEntry dfe = new DiskFreeEntry();
      dfe.id             = rs.getLong("id");
      dfe.hostname       = rs.getString("hostname");
      dfe.fileSystemName = rs.getString("file_system_name");
      dfe.totalSpace     = rs.getLong("total_space");
      dfe.spaceUsed      = rs.getLong("space_used");
      dfe.spaceFree      = rs.getLong("space_free");
      dfe.percentageUsed = rs.getBigDecimal("percentage_used");
      dfe.fileSystemRoot = rs.getString("file_system_root");
      dfe.when           = rs.getTimestamp("when");
      diskFreeEntries.put(dfe.fileSystemRoot, dfe);
    }
    rs.close();
    pst.close();
    return (diskFreeEntries);
  }

  /**
   * report recent diskfree for mount point
   * 
   * @param args[0] number of hours  (default is in properties file or 24 hours) 
   * @throws SQLException
   * @throws UnknownHostException
   * 
   */
  void report(String[] args) throws SQLException, UnknownHostException {

    con = getConnection();

    // make sure we have our table
    if (!checkTable()) {
      System.err.println("Could not find the table. Run 'service' first.");
      return;
    }

    // TODO: Allow reporting on other hosts
    String hostname = InetAddress.getLocalHost().getHostName();

    // get time in millis
    Timestamp then = null;
    Timestamp now = null;

    // hours back
    BigDecimal hoursBack=null;
    if (args.length > 0)
      hoursBack = new BigDecimal(args[0]);
    else if (dbp.getProperty("hours.back") != null)
      hoursBack = new BigDecimal(dbp.getProperty("report.hours"));
    if (hoursBack == null)
      hoursBack=new BigDecimal(24); // default
    BigDecimal secondsBack=hoursBack.multiply(new BigDecimal(3600)); 
   
    pst = con.prepareStatement("SELECT MAX(`when`) FROM diskfree "
        + "WHERE `when` < ( NOW() - INTERVAL ? SECOND) " + "AND `hostname` = ?");
    // then
    pst.setBigDecimal(1, secondsBack);
    pst.setString(2, hostname);
    rs = pst.executeQuery();
    if (rs.next()) {
      then = rs.getTimestamp(1);
    }
    rs.close();
    // now
    pst.setInt(1, 0);
    rs = pst.executeQuery();
    if (rs.next()) {
      now = rs.getTimestamp(1);
    }
    rs.close();
    pst.close();

    // if we don't have then but do have now, then shrink our window
    if (then == null && now != null) {
      pst = con.prepareStatement("SELECT MIN(`when`) FROM diskfree "
          + "WHERE `hostname` = ?");
      pst.setString(1, hostname);
      rs = pst.executeQuery();
      if (rs.next()) {
        then = rs.getTimestamp(1);
      }
    }

    // check to make sure we have both
    if (then == null || now == null) {
      System.err.println("We don't have enough data to report.");
      return;
    }

    // load then and now arrays
    HashMap<String, DiskFreeEntry> dfesThen = queryDiskFree(hostname, then);
    HashMap<String, DiskFreeEntry> dfesNow = queryDiskFree(hostname, now);

    // report header
    System.out.println("Hostname: " + dfesThen.get("/").hostname);
    System.out.println("    From: " + then);
    System.out.println("      To: " + now);
    
    BigDecimal millisToHours=
        new BigDecimal(60)
        .multiply(new BigDecimal(60))
        .multiply(new BigDecimal(1000));
    BigDecimal hoursDiff = 
        new BigDecimal(now.getTime() - then.getTime())
        .divide(millisToHours,2,BigDecimal.ROUND_HALF_UP);
    System.out.println("   Hours: " + hoursDiff.toString());
    System.out.println();

    // show volume growth
    String rFormat = "%12s %12s %5s %12s %8s %s\n";
    System.out.printf(rFormat, "1K-Used", "1K-Free", "Use%", "1K-Change", "Growth%", "Mounted on");
    for (String fsRoot : dfesThen.keySet()) {
      DiskFreeEntry dfeThen = dfesThen.get(fsRoot);
      DiskFreeEntry dfeNow = dfesNow.get(fsRoot);
      if (dfeNow != null) {
        long change = dfeNow.spaceUsed.intValue()
            - dfeThen.spaceUsed.intValue();
        String growth = "-";
        if (dfeNow.spaceUsed.intValue() > 0 
            && dfeThen.spaceUsed.intValue() > 0) {
          growth = new BigDecimal(dfeNow.spaceUsed.intValue())
              .divide(new BigDecimal(dfeThen.spaceUsed.intValue()), 3,
                  BigDecimal.ROUND_UP).subtract(new BigDecimal(1)).toString()
              + "%";
        }
        String used=dfeNow.percentageUsed
          .multiply(new BigDecimal(100)).setScale(0,BigDecimal.ROUND_UNNECESSARY).toString()+"%";
        System.out.printf(rFormat, dfeNow.spaceUsed, dfeNow.spaceFree, 
            used, change, growth, fsRoot);
      }
    }
  }

  /**
   * This method will cleanup the data table by deleting old records
   * 
   * @param args
   * @throws SQLException
   */
  void cleanup(String[] args) throws SQLException {

    con = getConnection();
    
    int keepDays=30; // default
    String strKeepDays=dbp.getProperty("keep.days");
    if (strKeepDays != null)
      keepDays = new Integer(strKeepDays).intValue();
    
    pst = con.prepareStatement(
        "DELETE FROM diskfree WHERE `when` < ( NOW() - INTERVAL ? DAY)");
    
    pst.setInt(1, keepDays);
    pst.execute();
    int updateCount=pst.getUpdateCount();
    if (updateCount > 0)
      System.out.println("Cleaned up "+updateCount+" old entries.");
    else
      System.out.println("No old entries to clean up.");
  }

  /**
   * Show usage
   */
  void usage() {
    
    System.out
        .print("Usage: java DiskFreeChecker {service | report {desired hours} | cleanup}\n\n");
  }

  /**
   * Shift 1st value off array.  Works like perl shift().
   * 
   */
  static String[] shift(String[] args) {
    String[] ret = new String[args.length - 1];
    if (args.length > 1)
      System.arraycopy(args, 1, ret, 0, args.length - 1);
    return (ret);
  }

  /**
   * Main entry point
   * 
   * @param args command line arguments
   * 
   * @throws ClassNotFoundException
   * @throws SQLException
   * @throws IOException
   */
  public static void main(String[] args) throws ClassNotFoundException,
      SQLException, IOException {
    DiskFreeChecker mdf = new DiskFreeChecker();
    // usage
    if (args.length == 0) {
      mdf.usage();
      System.exit(0);
    }
    // cleanup on exit
    mdf.registerShutdownHook();
    // service or report
    if ("service".equals(args[0]))
      mdf.service(shift(args));
    else if ("report".equals(args[0]))
      mdf.report(shift(args));
    else if ("cleanup".equals(args[0]))
      mdf.cleanup(shift(args));
    else
      mdf.usage();
  }

}