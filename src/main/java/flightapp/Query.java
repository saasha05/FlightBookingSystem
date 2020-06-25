package flightapp;
import java.io.*;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.xml.transform.Result;
import java.util.Comparator;
/**
 * Runs queries against a back-end database
 */
public class Query {
  // DB Connection
  private Connection conn;
  // Password hashing parameter constants
  private static final int HASH_STRENGTH = 65536;
  private static final int KEY_LENGTH = 128;
  // Canned queries
  private static final String CHECK_FLIGHT_CAPACITY = "SELECT capacity FROM Flights WHERE fid = ?";
  private PreparedStatement checkFlightCapacityStatement;
  // For check dangling
  private static final String TRANCOUNT_SQL = "SELECT @@TRANCOUNT AS tran_count";
  private PreparedStatement tranCountStatement;

  // Current user stuff
  private String currentUser = null;
  private int maxDeadlock = 3;
  public List<Itinerary> itineraries;

  private static final String BEGIN_TRANSACTION_SQL = "SET TRANSACTION ISOLATION LEVEL SERIALIZABLE; BEGIN TRANSACTION;";
  protected PreparedStatement beginTransactionStatement;

  private static final String COMMIT_SQL = "COMMIT TRANSACTION";
  protected PreparedStatement commitTransactionStatement;

  private static final String ROLLBACK_SQL = "ROLLBACK TRANSACTION";
  protected PreparedStatement rollbackTransactionStatement;

  private static final String DROP_TABLES = "DROP TABLE IF EXISTS Reservations;";
  protected PreparedStatement dropTablesStatement;

  private static final String RECREATE_RES_SQL = "CREATE TABLE Reservations (" +
          "id int IDENTITY(1,1) PRIMARY KEY," +
          "paid int," +
          "cancelled int," +
          "username varchar(30) FOREIGN KEY REFERENCES Users(username)," +
          "itineraryId int," +
          "price int," +
          "dayofmonth int);";
  protected PreparedStatement recreateReservationStatement;

  private static final String CLEARTABLES_SQL = "DELETE FROM Reservations; DELETE FROM Users;";
  protected PreparedStatement clearTablesStatement;

  // Create function
  private static final String CREATE_USER_SQL = "INSERT INTO Users VALUES ((?),(?),(?),(?))";
  protected PreparedStatement createUserStatement;

  private static final String CREATE_RESERVATION_SQL = "INSERT INTO Reservations VALUES ((?),(?),(?),(?),(?),(?))";
  protected PreparedStatement createReservationStatement;

  // Login function
  private static final String CHECKUSEREXIST_SQL = "SELECT * FROM Users WHERE username = ?";
  protected PreparedStatement userExistStatement;

  private static final String LOGIN_SQL = "SELECT * FROM Users WHERE username = ?";
  protected PreparedStatement loginStatement;

  // Search function
  private static final String DIRECT_FLIGHTS = "SELECT TOP (?) fid,day_of_month,carrier_id,flight_num,origin_city,dest_city,actual_time,capacity,price "
          + "FROM Flights "
          + "WHERE origin_city = ? AND dest_city = ? AND day_of_month =  ? AND canceled = 0 "
          + "ORDER BY actual_time ASC, fid ASC";
  protected PreparedStatement directFlightStatement;
  private static final String INDIRECT_FLIGHTS = "SELECT TOP (?) F.fid AS Ffid, F2.fid AS F2fid, F.day_of_month AS Fday_of_month, F.carrier_id AS Fcarrier_id, F2.carrier_id AS F2carrier_id, F.flight_num AS Fflight_num, F2.flight_num AS F2flight_num, F.origin_city AS Forigin_city, F2.dest_city AS F2dest_city, F.dest_city AS Fdest_city, F.actual_time AS Factual_time, F2.actual_time AS F2actual_time, F.capacity AS Fcapacity, F2.capacity AS F2capacity, F.price AS Fprice, F2.price AS F2price "
          + "FROM Flights AS F, Flights AS F2 "
          + "WHERE F.origin_city = ? AND F2.dest_city = ? AND F.day_of_month = ? AND F2.day_of_month = F.day_of_month AND F2.origin_city = F.dest_city AND F.canceled = 0 AND F2.canceled = 0 "
          + "ORDER BY F.actual_time + F2.actual_time ASC ";
  protected PreparedStatement indirectFlightStatement;
  // Pay function
  private static final String SEARCH_USERNAME_RESERVATION_UNPAID = "SELECT * "
          + "FROM Reservations as R where R.id = ? AND R.username = ? AND R.paid = 0";
  private PreparedStatement searchUsernameWithReservationUnpaidStatement;

  private static final String SEARCH_USER_BALANCE = "SELECT U.balance FROM Users as U WHERE U.username = ? ";
  private PreparedStatement searchUserBalanceStatement;

  private static final String UPDATE_USER_BALANCE = "UPDATE Users SET balance = ? WHERE username = ?";
  private PreparedStatement updateUserBalanceStatement;

  private static final String UPDATE_PAY_STATUS = "UPDATE Reservations SET paid = ? WHERE username = ? AND id = ?";
  private PreparedStatement updatePayStatusStatement;

  // Cancel function
  private static final String UPDATE_CANCELLED_STATUS = "UPDATE Reservations SET cancelled = ? WHERE username = ? AND id = ?";
  private PreparedStatement updateCancelledStatusStatement;

  private static final String SEARCH_RESERVATION_ID = "SELECT * FROM Reservations WHERE username = ? AND id = ?";
  private PreparedStatement searchReservationId;

  // Reservation function
  private static final String NUM_RESERVATIONS_SQL = "SELECT COUNT(*) AS count FROM Reservations";
  protected  PreparedStatement numReservationsStatement;

  private static final String SEARCH_USER_RESERVATION_SQL = "SELECT * FROM Reservations WHERE username = ?";
  protected PreparedStatement searchReservationsForUserStatement;

  private static final String GET_ALL_RESERVATIONS = "SELECT * FROM Reservations";
  protected PreparedStatement getAllReservations;

  private static final String SEARCH_USER_RESERVATION_DAY_SQL = "SELECT * FROM Reservations AS R WHERE R.username = ? AND R.dayofmonth = ?";
  protected PreparedStatement searchReservationsForUserDayStatement;

  private static final String SEARCH_FLIGHT_SQL = "SELECT * FROM Flights WHERE fid = ?";
  protected PreparedStatement searchFlightStatement;

  public Query() throws SQLException, IOException {
    this(null, null, null, null);
  }
  protected Query(String serverURL, String dbName, String adminName, String password)
          throws SQLException, IOException {
    conn = serverURL == null ? openConnectionFromDbConn()
            : openConnectionFromCredential(serverURL, dbName, adminName, password);
    prepareStatements();
  }
  /**
   * Return a connecion by using dbconn.properties file
   *
   * @throws SQLException
   * @throws IOException
   */
  public static Connection openConnectionFromDbConn() throws SQLException, IOException {
    // Connect to the database with the provided connection configuration
    Properties configProps = new Properties();
    configProps.load(new FileInputStream("dbconn.properties"));
    String serverURL = configProps.getProperty("flightapp.server_url");
    String dbName = configProps.getProperty("flightapp.database_name");
    String adminName = configProps.getProperty("flightapp.username");
    String password = configProps.getProperty("flightapp.password");
    return openConnectionFromCredential(serverURL, dbName, adminName, password);
  }
  /**
   * Return a connecion by using the provided parameter.
   *
   * @param serverURL example: example.database.widows.net
   * @param dbName    database name
   * @param adminName username to login server
   * @param password  password to login server
   *
   * @throws SQLException
   */
  protected static Connection openConnectionFromCredential(String serverURL, String dbName,
                                                           String adminName, String password) throws SQLException {
    String connectionUrl =
            String.format("jdbc:sqlserver://%s:1433;databaseName=%s;user=%s;password=%s", serverURL,
                    dbName, adminName, password);
    Connection conn = DriverManager.getConnection(connectionUrl);
    // By default, automatically commit after each statement
    conn.setAutoCommit(true);
    // By default, set the transaction isolation level to serializable
    conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    return conn;
  }
  /**
   * Get underlying connection
   */
  public Connection getConnection() {
    return conn;
  }
  /**
   * Closes the application-to-database connection
   */
  public void closeConnection() throws SQLException {
    conn.close();
  }
  /**
   * Clear the data in any custom tables created.
   *
   * WARNING! Do not drop any tables and do not clear the flights table.
   */
  public void clearTables() {
    try {
      beginTransaction();
      dropTablesStatement.executeUpdate();
      recreateReservationStatement.executeUpdate();
      clearTablesStatement.executeUpdate();
      maxDeadlock = 3;
      commitTransaction();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  private void prepareStatements() throws SQLException {
    checkFlightCapacityStatement = conn.prepareStatement(CHECK_FLIGHT_CAPACITY);
    tranCountStatement = conn.prepareStatement(TRANCOUNT_SQL);
    beginTransactionStatement = conn.prepareStatement(BEGIN_TRANSACTION_SQL);
    commitTransactionStatement = conn.prepareStatement(COMMIT_SQL);
    rollbackTransactionStatement = conn.prepareStatement(ROLLBACK_SQL);
    clearTablesStatement = conn.prepareStatement(CLEARTABLES_SQL);
    numReservationsStatement = conn.prepareStatement(NUM_RESERVATIONS_SQL);
    createUserStatement = conn.prepareStatement(CREATE_USER_SQL);
    loginStatement = conn.prepareStatement(LOGIN_SQL);
    userExistStatement = conn.prepareStatement(CHECKUSEREXIST_SQL);
    directFlightStatement = conn.prepareStatement(DIRECT_FLIGHTS);
    indirectFlightStatement = conn.prepareStatement(INDIRECT_FLIGHTS);
    searchUsernameWithReservationUnpaidStatement = conn.prepareStatement(SEARCH_USERNAME_RESERVATION_UNPAID);
    searchUserBalanceStatement = conn.prepareStatement(SEARCH_USER_BALANCE);
    updateUserBalanceStatement = conn.prepareStatement(UPDATE_USER_BALANCE);
    updatePayStatusStatement = conn.prepareStatement(UPDATE_PAY_STATUS);
    searchReservationsForUserStatement = conn.prepareStatement(SEARCH_USER_RESERVATION_SQL);
    searchFlightStatement = conn.prepareStatement(SEARCH_FLIGHT_SQL);
    createReservationStatement = conn.prepareStatement(CREATE_RESERVATION_SQL);
    searchReservationId = conn.prepareStatement(SEARCH_RESERVATION_ID);
    updateCancelledStatusStatement = conn.prepareStatement(UPDATE_CANCELLED_STATUS);
    recreateReservationStatement = conn.prepareStatement(RECREATE_RES_SQL);
    dropTablesStatement = conn.prepareStatement(DROP_TABLES);
    searchReservationsForUserDayStatement = conn.prepareStatement(SEARCH_USER_RESERVATION_DAY_SQL);
    getAllReservations = conn.prepareStatement(GET_ALL_RESERVATIONS);
  }
  /**
   * Takes a user's username and password and attempts to log the user in.
   *
   * @param username user's username
   * @param password user's password
   *
   * @return If someone has already logged in, then return "User already logged in\n" For all other
   *         errors, return "Login failed\n". Otherwise, return "Logged in as [username]\n".
   */
  public String transaction_login(String username, String password) {
    if(currentUser != null) {
      return "User already logged in\n";
    }
    username = username.toLowerCase();
    try {
      beginTransaction();
      loginStatement.clearParameters();
      loginStatement.setString(1, username);
      try {
        ResultSet result = loginStatement.executeQuery();
        if (!result.next()){
          result.close();
          rollbackTransaction();
        } else {
          String storedHash = result.getString("hash");
          String storedSalt = result.getString("salt");
          String[] currHash = generateHash(password, decodeHexString(storedSalt));
          result.close();
          if(currHash[0].equals(storedHash)) {
            currentUser = username;
            commitTransaction();
            return "Logged in as " + currentUser + "\n";
          }
          commitTransaction();
        }
        return "Login failed\n";
      } catch(SQLException e) {
        if(isDeadLock(e) && maxDeadlock > 0) {
          try {
            rollbackTransaction();
            return transaction_login(username, password);
          } catch(SQLException rollbackErr) {
            return rollbackErr.getMessage();
          }
        }
        e.printStackTrace();
      }
    } catch(SQLException e) {
      e.printStackTrace();
    }
    return "Login failed\n";
  }


  /**
   * Implement the create user function.
   *
   * @param username   new user's username. User names are unique the system.
   * @param password   new user's password.
   * @param initAmount initial amount to deposit into the user's account, should be >= 0 (failure
   *                   otherwise).
   *
   * @return either "Created user {@code username}\n" or "Failed to create user\n" if failed.
   */
  public String transaction_createCustomer(String username, String password, int initAmount) {
    if (initAmount < 0) {
      return "Failed to create user\n";
    }
    try {
      beginTransaction();
      userExistStatement.clearParameters();
      userExistStatement.setString(1, username.toLowerCase());
      ResultSet Results = userExistStatement.executeQuery();
      if (Results.next()) {
        return "Failed to create user\n";
      }
      // Create a new user
      Results.close();
      createUserStatement.clearParameters();
      createUserStatement.setString(1, username.toLowerCase());
      // returns hash password, salt
      String[] key = generateHash(password);
      createUserStatement.setString(2, key[0]);
      createUserStatement.setString(3, key[1]);
      createUserStatement.setInt(4, initAmount);
      try {
        createUserStatement.executeUpdate();
        commitTransaction();
        return "Created user " + username + "\n";
      } catch(SQLException deadlock) {
        if(isDeadLock(deadlock) && maxDeadlock > 0) {
          maxDeadlock--;
          return transaction_createCustomer(username, password, initAmount);
        }
      }
      rollbackTransaction();
      return "Failed to create user\n";
    } catch (SQLException e) {e.printStackTrace();}
    return "Failed to create user\n";
  }

  /**
   * Implement the search function.
   *
   * Searches for flights from the given origin city to the given destination city, on the given day
   * of the month. If {@code directFlight} is true, it only searches for direct flights, otherwise
   * is searches for direct flights and flights with two "hops." Only searches for up to the number
   * of itineraries given by {@code numberOfItineraries}.
   *
   * The results are sorted based on total flight time.
   *
   * @param originCity
   * @param destinationCity
   * @param directFlight        if true, then only search for direct flights, otherwise include
   *                            indirect flights as well
   * @param dayOfMonth
   * @param numberOfItineraries number of itineraries to return
   *
   * @return If no itineraries were found, return "No flights match your selection\n". If an error
   *         occurs, then return "Failed to search\n".
   *
   *         Otherwise, the sorted itineraries printed in the following format:
   *
   *         Itinerary [itinerary number]: [number of flights] flight(s), [total flight time]
   *         minutes\n [first flight in itinerary]\n ... [last flight in itinerary]\n
   *
   *         Each flight should be printed using the same format as in the {@code Flight} class.
   *         Itinerary numbers in each search should always start from 0 and increase by 1.
   *
   * @see Flight#toString()
   */
  public String transaction_search(String originCity, String destinationCity, boolean directFlight,
                                   int dayOfMonth, int numberOfItineraries) {
    if (numberOfItineraries <= 0) {
      return "No flights match your selection\n";
    }
    itineraries = new ArrayList<Itinerary>();
    StringBuilder sb = new StringBuilder();
    try {
      // find direct flights
      ArrayList<Flight[]> flightsList = new ArrayList<Flight[]>();
      flightsList.addAll(findDirectFlights(originCity, destinationCity, dayOfMonth, numberOfItineraries));

      // change number of itineraries so that future calculations have right number of flights
      int remainingItineraries = numberOfItineraries - flightsList.size();
      if(!directFlight && remainingItineraries > 0 ) {
        flightsList.addAll(findIndirectFlights(originCity, destinationCity, dayOfMonth, remainingItineraries));
      }
      // add everything to itineraries and build string
      itineraries.sort(Itinerary::compareTo);
      for(int i = 0; i < itineraries.size(); i++) {
        Itinerary it = itineraries.get(i);
        sb.append("Itinerary " + i + ": "+ it.numFlights + " flight(s), " + it.totalTime +
                " minutes\n");
        sb.append(it.f1.toString() + "\n");
        if(it.numFlights == 2) {
          sb.append(it.f2.toString() + "\n");
        }
      }
    } finally {
      checkDanglingTransaction();
    }
    return sb.toString();
  }

  /**
   * Implements the book itinerary function.
   *
   * @param itineraryId ID of the itinerary to book. This must be one that is returned by search in
   *                    the current session.
   *
   * @return If the user is not logged in, then return "Cannot book reservations, not logged in\n".
   *         If the user is trying to book an itinerary with an invalid ID or without having done a
   *         search, then return "No such itinerary {@code itineraryId}\n". If the user already has
   *         a reservation on the same day as the one that they are trying to book now, then return
   *         "You cannot book two flights in the same day\n". For all other errors, return "Booking
   *         failed\n".
   *
   *         And if booking succeeded, return "Booked flight(s), reservation ID: [reservationId]\n"
   *         where reservationId is a unique number in the reservation system that starts from 1 and
   *         increments by 1 each time a successful reservation is made by any user in the system.
   */
  public String transaction_book(int itineraryId) {
    if (currentUser == null) {
      return "Cannot book reservations, not logged in\n";
    }
    if (itineraries == null || itineraryId > this.itineraries.size()  || itineraryId < 0){
        return "No such itinerary " + itineraryId + "\n";
    }
    Itinerary it = itineraries.get(itineraryId);
    if(it == null) {
      return "Booking failed\n";
    }
    Flight f1 = it.f1;
    Flight f2 = it.f2;
    try {
      beginTransaction();
      // check for capacity
      int count1 = 0;
      int count2 = 0;
      ResultSet reservationList = getAllReservations.executeQuery();
      while (reservationList.next()) {
        if (itineraries.get(reservationList.getInt("itineraryId")).f1 == f1 || itineraries.get(reservationList.getInt("itineraryId")).f2 == f1){
          count1++;
        }
        if (itineraries.get(reservationList.getInt("itineraryId")).f1 == f2 || itineraries.get(reservationList.getInt("itineraryId")).f2 == f2){
          count2++;
        }
      }
      reservationList.close();
      if (f1.capacity - count1 <= 0 || (f2 != null && f2.capacity - count2 <= 0)) {
        rollbackTransaction();
        return "Booking failed\n";
      }
      // booking in the same day
      searchReservationsForUserDayStatement.clearParameters();
      searchReservationsForUserDayStatement.setString(1, currentUser);
      searchReservationsForUserDayStatement.setInt(2, it.day);
      try {
        ResultSet reservations = searchReservationsForUserDayStatement.executeQuery();
        if(reservations.next()) {
          reservations.close();
          rollbackTransaction();
          return "You cannot book two flights in the same day\n";
        } else {
          reservations.close();
        }
      } catch(SQLException e) {
        rollbackTransaction();
        if(isDeadLock(e)) {
          return transaction_book(itineraryId);
        }
        e.printStackTrace();
      }
      // create reservation
      int nextReserve;
      numReservationsStatement.clearParameters();
      ResultSet numRes = numReservationsStatement.executeQuery();
      if(numRes.next()) {
        nextReserve = numRes.getInt("count");
      } else {
        nextReserve = 0;
      }
      createReservationStatement.clearParameters();
      createReservationStatement.setInt(1, 0);
      createReservationStatement.setInt(2, 0);
      createReservationStatement.setString(3, currentUser);
      createReservationStatement.setInt(4, itineraryId);
      createReservationStatement.setInt(5, it.price);
      createReservationStatement.setInt(6, it.day);
      try {
        createReservationStatement.executeUpdate();
        commitTransaction();
        return "Booked flight(s), reservation ID: " + (nextReserve + 1) + "\n";
      } catch(SQLException e) {
        if(isDeadLock(e)) {
          return transaction_book(itineraryId);
        }
        e.printStackTrace();
      }
    } catch(SQLException e) {
      e.printStackTrace();
    } finally {
      checkDanglingTransaction();
    }
    return "Booking failed \n";
  }
  /**
   * Implements the pay function.
   *
   * @param reservationId the reservation to pay for.
   *
   * @return If no user has logged in, then return "Cannot pay, not logged in\n" If the reservation
   *         is not found / not under the logged in user's name, then return "Cannot find unpaid
   *         reservation [reservationId] under user: [username]\n" If the user does not have enough
   *         money in their account, then return "User has only [balance] in account but itinerary
   *         costs [cost]\n" For all other errors, return "Failed to pay for reservation
   *         [reservationId]\n"
   *
   *         If successful, return "Paid reservation: [reservationId] remaining balance:
   *         [balance]\n" where [balance] is the remaining balance in the user's account.
   */
  public String transaction_pay(int reservationId) {
    if(currentUser == null) {
      return "Cannot pay, not logged in\n";
    }
    try {
      beginTransaction();
      // get reservation from reservation id
      searchUsernameWithReservationUnpaidStatement.clearParameters();
      searchUsernameWithReservationUnpaidStatement.setInt(1, reservationId);
      searchUsernameWithReservationUnpaidStatement.setString(2, currentUser);
      ResultSet reservationResult = searchUsernameWithReservationUnpaidStatement.executeQuery();
      if(!reservationResult.next()) {
        commitTransaction();
        reservationResult.close();
        return "Cannot find unpaid reservation " + reservationId + " under user: " + currentUser + "\n";
      } else {
        int reservationPrice = reservationResult.getInt("price");
        // find balance in user account
        searchUserBalanceStatement.clearParameters();
        searchUserBalanceStatement.setString(1, currentUser);
        ResultSet userBalanceResult = searchUserBalanceStatement.executeQuery();
        userBalanceResult.next();
        int userBalance = userBalanceResult.getInt("balance");
        userBalanceResult.close();
        // check if user has enough balance
        int newUserBalance = userBalance - reservationPrice;
        if(newUserBalance < 0) {
          rollbackTransaction();
          return "User has only " + userBalance + " in account but itinerary costs " + reservationPrice + "\n";
        }
        // update user balance
        updateUserBalanceStatement.clearParameters();
        updateUserBalanceStatement.setInt(1, newUserBalance);
        updateUserBalanceStatement.setString(2, currentUser);
        updateUserBalanceStatement.executeUpdate();
        // update pay status of reservation
        updatePayStatusStatement.clearParameters();
        updatePayStatusStatement.setInt(1, 1); // 1 means paid
        updatePayStatusStatement.setString(2, currentUser);
        updatePayStatusStatement.setInt(3, reservationId);
        updatePayStatusStatement.executeUpdate();
        // Complete pay
        commitTransaction();
        return "Paid reservation: " + reservationId + " remaining balance: " + newUserBalance + "\n";
      }
    } catch(SQLException e) {
      e.printStackTrace();
      return "Failed to pay reservation " + reservationId + "\n";
    } finally {
      checkDanglingTransaction();
    }
  }
  /**
   * Implements the reservations function.
   *
   * @return If no user has logged in, then return "Cannot view reservations, not logged in\n" If
   *         the user has no reservations, then return "No reservations found\n" For all other
   *         errors, return "Failed to retrieve reservations\n"
   *
   *         Otherwise return the reservations in the following format:
   *
   *         Reservation [reservation ID] paid: [true or false]:\n [flight 1 under the
   *         reservation]\n [flight 2 under the reservation]\n Reservation [reservation ID] paid:
   *         [true or false]:\n [flight 1 under the reservation]\n [flight 2 under the
   *         reservation]\n ...
   *
   *         Each flight should be printed using the same format as in the {@code Flight} class.
   *
   * @see Flight#toString()
   */
  public String transaction_reservations() {
    if(currentUser == null) {
      return "Cannot view reservations, not logged in\n";
    }
    try {
      beginTransaction();
      searchReservationsForUserStatement.clearParameters();
      searchReservationsForUserStatement.setString(1, currentUser);
      ResultSet reservations = searchReservationsForUserStatement.executeQuery();
      StringBuffer sb = new StringBuffer();
      while(reservations.next()) {
        String resId = reservations.getString("id");
        String ifPaid = (reservations.getInt("paid") == 1) ? "true" : "false";
        sb.append("Reservation " + resId + " paid: " + ifPaid + ":\n");
        Itinerary currIt = itineraries.get(reservations.getInt("itineraryId"));
        if(currIt == null) {
          rollbackTransaction();
          return "Failed to retrieve reservations\n";
        }
        sb.append(currIt.f1.toString() + "\n");
        if(currIt.f2 != null) {
          sb.append(currIt.f2.toString() + "\n");
        }
      }
      reservations.close();
      commitTransaction();
      return sb.toString();
    } catch(SQLException e) {
      e.printStackTrace();
      return "Failed to retrieve reservations\n";
    } finally {
      checkDanglingTransaction();
    }
  }
  /**
   * Implements the cancel operation.
   *
   * @param reservationId the reservation ID to cancel
   *
   * @return If no user has logged in, then return "Cannot cancel reservations, not logged in\n" For
   *         all other errors, return "Failed to cancel reservation [reservationId]\n"
   *
   *         If successful, return "Canceled reservation [reservationId]\n"
   *
   *         Even though a reservation has been canceled, its ID should not be reused by the system.
   */
  public String transaction_cancel(int reservationId) {
    try {
      if (currentUser == null) {
        return "Cannot cancel reservations, not logged in\n";
      }	else {
        beginTransaction();
        searchUserBalanceStatement.clearParameters();
        searchUserBalanceStatement.setString(1, currentUser);
        ResultSet balance = searchUserBalanceStatement.executeQuery();
        balance.next();
        int userBalance = balance.getInt("balance");
        balance.close();
        searchReservationId.clearParameters();
        searchReservationId.setString(1, currentUser);
        searchReservationId.setInt(2, reservationId);
        ResultSet currBalance = searchReservationId.executeQuery();
        if (!currBalance.next() || currBalance.getInt("cancelled") != 0) {
          rollbackTransaction();
          return "Failed to cancel reservation " + reservationId + "\n";
        }
        Itinerary currIt = itineraries.get(currBalance.getInt("itineraryId"));
        currBalance.close();
        updateUserBalanceStatement.setInt(1, userBalance + currIt.price);
        updateCancelledStatusStatement.clearParameters();
        updateCancelledStatusStatement.setInt(1, 1);
        updateCancelledStatusStatement.setString(2, currentUser);
        updateCancelledStatusStatement.setInt(3, reservationId);
        updateCancelledStatusStatement.executeUpdate();
        commitTransaction();
        return "Canceled reservation " + reservationId + "\n";
      }
    } catch(SQLException e) {
      try {
        rollbackTransaction();
      } catch(SQLException err) {
        e.printStackTrace();
      }
      if(isDeadLock(e)) {
        return transaction_cancel(reservationId);
      }
      return "Failed to cancel reservation " + reservationId + "\n";
    } finally {
      checkDanglingTransaction();
    }
  }

  /**
   * PRIVATE METHODS
   */
  private ArrayList<Flight[]> findDirectFlights(String originCity, String destinationCity, int dayOfMonth,
                                                int numberOfItineraries) {
    ArrayList<Flight[]> listOfFlights = new ArrayList<Flight[]>();
    try {
      beginTransaction();
      directFlightStatement.clearParameters();
      directFlightStatement.setInt(1, numberOfItineraries);
      directFlightStatement.setString(2, originCity);
      directFlightStatement.setString(3, destinationCity);
      directFlightStatement.setInt(4, dayOfMonth);
      try {
        ResultSet results = directFlightStatement.executeQuery();
        while (results.next()) {
          Flight[] container = new Flight[1];
          Flight currFlight = getFlightFromResultSet(results);
          itineraries.add(new Itinerary(currFlight, null));
          container[0] = currFlight;
          listOfFlights.add(container);
        }
        results.close();
        commitTransaction();
        return listOfFlights;
      } catch(SQLException e) {
        rollbackTransaction();
        if(isDeadLock(e)) {
          return findDirectFlights(originCity, destinationCity, dayOfMonth, numberOfItineraries);
        }
        e.printStackTrace();
      }
      return listOfFlights;
    } catch(SQLException e) {
      if(isDeadLock(e)) {
        return findDirectFlights(originCity, destinationCity, dayOfMonth, numberOfItineraries);
      }
      e.printStackTrace();
      return listOfFlights;
    }
  }
  private ArrayList<Flight[]> findIndirectFlights(String originCity, String destinationCity, int dayOfMonth,
                                                  int numberOfItineraries) {

    ArrayList<Flight[]> listOfFlights = new ArrayList<Flight[]>();
    try {
      beginTransaction();
      indirectFlightStatement.clearParameters();
      indirectFlightStatement.setInt(1, numberOfItineraries);
      indirectFlightStatement.setString(2, originCity);
      indirectFlightStatement.setString(3, destinationCity);
      indirectFlightStatement.setInt(4, dayOfMonth);
      try {
        ResultSet results = indirectFlightStatement.executeQuery();
        while (results.next()) {
          Flight[] container = new Flight[2];
          Flight firstFlight = new Flight();
          Flight secondFlight = new Flight();
          firstFlight.fid = results.getInt("Ffid");
          firstFlight.dayOfMonth = results.getInt("Fday_of_month");
          firstFlight.carrierId = results.getString("Fcarrier_id");
          firstFlight.flightNum = results.getString("Fflight_num");
          firstFlight.originCity = results.getString("Forigin_city");
          firstFlight.destCity = results.getString("Fdest_city");
          firstFlight.time = results.getInt("Factual_time");
          firstFlight.capacity = results.getInt("Fcapacity");
          firstFlight.price = results.getInt("Fprice");
          container[0] = firstFlight;
          secondFlight.fid = results.getInt("F2fid");
          secondFlight.dayOfMonth = results.getInt("Fday_of_month");
          secondFlight.carrierId = results.getString("F2carrier_id");
          secondFlight.flightNum = results.getString("F2flight_num");
          secondFlight.originCity = results.getString("Fdest_city");
          secondFlight.destCity = results.getString("F2dest_city");
          secondFlight.time = results.getInt("F2actual_time");
          secondFlight.capacity = results.getInt("F2capacity");
          secondFlight.price = results.getInt("F2price");
          itineraries.add(new Itinerary(firstFlight, secondFlight));
          container[1] = secondFlight;
          listOfFlights.add(container);
        }
        results.close();
        commitTransaction();
        return listOfFlights;
      } catch(SQLException e) {
        if(isDeadLock(e)) {
          rollbackTransaction();
          return findIndirectFlights(originCity, destinationCity, dayOfMonth, numberOfItineraries);
        }
      }
    } catch(SQLException e) {
      e.printStackTrace();
    }
    return findIndirectFlights(originCity, destinationCity, dayOfMonth, numberOfItineraries);
  }

  private Flight getFlightFromResultSet(ResultSet results) {
    Flight flight = new Flight();
    try {
      flight.fid = results.getInt("fid");
      flight.dayOfMonth = results.getInt("day_of_month");
      flight.carrierId = results.getString("carrier_id");
      flight.flightNum = results.getString("flight_num");
      flight.originCity = results.getString("origin_city");
      flight.destCity = results.getString("dest_city");
      flight.time = results.getInt("actual_time");
      flight.capacity = results.getInt("capacity");
      flight.price = results.getInt("price");
    } catch (SQLException e) {
      e.printStackTrace();
    }
    return flight;
  }
  /**
   * Example utility function that uses prepared statements
   */
  private int checkFlightCapacity(int fid) throws SQLException {
    checkFlightCapacityStatement.clearParameters();
    checkFlightCapacityStatement.setInt(1, fid);
    ResultSet results = checkFlightCapacityStatement.executeQuery();
    results.next();
    int capacity = results.getInt("capacity");
    results.close();
    return capacity;
  }
  // Passwords Stuff
  private String[] generateHash(String password) {
    // Generate a random cryptographic salt
    SecureRandom random = new SecureRandom();
    byte[] salt = new byte[16];
    random.nextBytes(salt);
    return generateHash(password, salt);
  }
  private String[] generateHash(String password, byte[] salt) {
    // Specify the hash parameters
    KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_STRENGTH, KEY_LENGTH);
    // Generate the hash
    SecretKeyFactory factory = null;
    byte[] hash = null;
    try {
      factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
      hash = factory.generateSecret(spec).getEncoded();
    } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
      throw new IllegalStateException();
    }
    return new String[]{encodeHexString(hash), encodeHexString(salt)};
  }
  // Methods taken from https://www.baeldung.com/java-byte-arrays-hex-strings
  // Takes an array of bytes and converts that into a hexadecimal string
  public String encodeHexString(byte[] bytes) {
    BigInteger bigInteger = new BigInteger(1, bytes);
    return bigInteger.toString(16);
  }
  // Takes a hexadecimal string and converts it into a byte array
  public byte[] decodeHexString(String hexString) {
    byte[] byteArray = new BigInteger(hexString, 16)
            .toByteArray();
    if (byteArray[0] == 0) {
      byte[] output = new byte[byteArray.length - 1];
      System.arraycopy(
              byteArray, 1, output,
              0, output.length);
      return output;
    }
    return byteArray;
  }

  // Transaction stuff
  private void beginTransaction() throws SQLException {
    conn.setAutoCommit(false);
    beginTransactionStatement.executeUpdate();
  }
  public void commitTransaction() throws SQLException {
    commitTransactionStatement.executeUpdate();
    conn.setAutoCommit(true);
  }
  public void rollbackTransaction() throws SQLException {
    rollbackTransactionStatement.executeUpdate();
    conn.setAutoCommit(true);
  }
  /**
   * Throw IllegalStateException if transaction not completely complete, rollback.
   *
   */
  private void checkDanglingTransaction() {
    try {
      try (ResultSet rs = tranCountStatement.executeQuery()) {
        rs.next();
        int count = rs.getInt("tran_count");
        if (count > 0) {
          throw new IllegalStateException(
                  "Transaction not fully commit/rollback. Number of transaction in process: " + count);
        }
      } finally {
        conn.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("Database error", e);
    }
  }
  private static boolean isDeadLock(SQLException ex) {
    return ex.getErrorCode() == 1205;
  }
  /**
   * A class to store flight information.
   */
  class Flight {
    public int fid;
    public int dayOfMonth;
    public String carrierId;
    public String flightNum;
    public String originCity;
    public String destCity;
    public int time;
    public int capacity;
    public int price;
    @Override
    public String toString() {
      return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId + " Number: "
              + flightNum + " Origin: " + originCity + " Dest: " + destCity + " Duration: " + time
              + " Capacity: " + capacity + " Price: " + price;
    }
  }
  /**
   * A class to store itinerary information.
   */
  class Itinerary  {
    public Flight f1;
    public Flight f2;
    public int day;
    public int price;
    public int totalTime;
    public int numFlights;
    Itinerary(Flight flight1, Flight flight2) {
      f1 = flight1;
      f2 = flight2;
      day = flight1.dayOfMonth;
      price = flight1.price;
      totalTime = flight1.time;
      numFlights = 1;
      if (flight2 != null) {
        price += flight2.price;
        totalTime += flight2.time;
        numFlights = 2;
      }
    }
    public int compareTo(Itinerary i) {
      return totalTime - i.totalTime;
    }
  }
}

