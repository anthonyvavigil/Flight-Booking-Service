package flightapp;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.security.*;
import java.security.spec.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * Runs queries against a back-end database
 */
public class Query {
	// DB Connection
	private Connection conn;

	// Password hashing parameter constants
	private static final int HASH_STRENGTH = 65536;
  private static final int KEY_LENGTH = 128;
  String currUser = "";

	public ArrayList<Itinerary> lastRS;
	
	// Canned queries
	private static final String CHECK_FLIGHT_CAPACITY = "SELECT capacity FROM Flights WHERE fid = ?";
	private PreparedStatement checkFlightCapacityStatement;

	// For check dangling
	private static final String TRANCOUNT_SQL = "SELECT @@TRANCOUNT AS tran_count";
	private PreparedStatement tranCountStatement;

	// clear tables
	private static final String CLEAR_TABLES_SQL = "DELETE FROM Reservations;DELETE FROM Users;DBCC CHECKIDENT (Reservations, RESEED, 1)";
	private PreparedStatement clear;
	
	// login
	private static final String LOGIN_SQL = "SELECT hash, salt FROM Users WHERE username = ?";
	private PreparedStatement login;
	
	// create
	private static final String CREATE_SQL = "INSERT INTO Users Values (?, ?, ?, ?)";
	private PreparedStatement createUser;
	
	// direct search
	private static final String SEARCH_DIRECT = "SELECT TOP (?) fid, day_of_month,carrier_id,flight_num,origin_city,dest_city,actual_time,capacity,price "
			+ "FROM Flights WHERE origin_city = ? AND dest_city = ? AND day_of_month =? AND canceled = 0 ORDER BY actual_time ASC";
	private PreparedStatement directSearchSQL;
	
	// one hop search
	private static final String SEARCH_ONE_HOP = "SELECT TOP (?)" + 
			"	F1.fid AS f1_id, F2.fid AS f2_id," + 
			"	F1.day_of_month AS f1_day_of_month, F2.day_of_month AS f2_day_of_month," + 
			"	F1.carrier_id AS f1_carrier_id, F2.carrier_id AS f2_carrier_id," + 
			"	F1.flight_num AS f1_flight_num, F2.flight_num AS f2_flight_num," + 
			"	F1.actual_time AS f1_time, F2.actual_time AS f2_time," + 
			"	F1.capacity AS f1_capacity, F2.capacity AS f2_capacity," + 
			"	F1.price AS f1_price, F2.price AS f2_price," + 
			"	F1.origin_city AS f1_origin_city, F2.origin_city AS f2_origin_city," + 
			"	F1.dest_city AS f1_dest_city, F2.dest_city AS f2_dest_city," + 
			"   (F1.actual_time + F2.actual_time) AS total_time" + 
			"	FROM	Flights AS F1 JOIN Flights AS F2" + 
			"	ON	F1.dest_city = F2.origin_city AND F1.day_of_month = F2.day_of_month" + 
			"	WHERE	F1.origin_city = ? AND F2.dest_city = ? AND F1.day_of_month = ? AND F1.canceled = 0 AND F2.canceled = 0" + 
			"	ORDER BY total_time, f1_id, f2_id";
	private PreparedStatement oneHopSearchSQL;
	
	private static final String CHECK_RESERVATION_DATE = "SELECT COUNT(*) AS sum FROM Reservations WHERE username = ? AND day_of_month = ?";
	private PreparedStatement checkReservationDate;
	
	private static final String CHECK_AVAILABILITY = "SELECT capacity FROM Flights WHERE fid = ? OR fid = ?";
	private PreparedStatement checkAvailability;
	
	private static final String CHECK_AVAILABILITY_RESERVATIONS_1 = "SELECT COUNT(rid) AS count_rid FROM Reservations WITH (UPDLOCK, ROWLOCK) WHERE f1_id = ?";
	private PreparedStatement checkAvailabilityReservations1;
	
	private static final String CHECK_AVAILABILITY_RESERVATIONS_2 = "SELECT COUNT(rid) AS count_rid FROM Reservations WHERE f2_id = ?";
	private PreparedStatement checkAvailabilityReservations2;
	
	private static final String BOOK_ITINERARY = "INSERT INTO Reservations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
	private PreparedStatement bookItinerary;
	
	private static final String CHANGE_BALANCE = "UPDATE Users SET balance = balance - ? WHERE username = ?";
	private PreparedStatement changeBalance;
	
	private static final String GET_RESERVATION_FROM_USER = "SELECT * FROM Reservations AS R WHERE username = ?";
	private PreparedStatement getReservationFromUser;
	
	private static final String GET_RESERVATION = "SELECT * FROM Reservations WHERE rid = ? AND username = ?";
	private PreparedStatement getReservation;
	
	private static final String GET_USER = "SELECT * FROM Users WHERE username = ?";
	private PreparedStatement getUser;
	
	private static final String CHANGE_BALANCE_RESERVATION = "UPDATE Reservations SET amtUnpaid = amtUnpaid - ?, amtPaid = amtPaid + ? WHERE rid = ?";
	private PreparedStatement changeBalanceReservation;
	
	private static final String NEXT_ID_RES = "SELECT MAX(rid) AS max_rid FROM Reservations";
	private PreparedStatement nextIdReservation;
	
	private static final String GET_FLIGHT_INFO = "SELECT * FROM Flights WHERE fid = ?";
	private PreparedStatement getFlightInfo;

	private static final String DELETE_RESERVATION = "DELETE FROM Reservations WHERE rid = ? AND username = ?";
	private PreparedStatement deleteReservation;

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
		String serverURL = configProps.getProperty("hw5.server_url");
		String dbName = configProps.getProperty("hw5.database_name");
		String adminName = configProps.getProperty("hw5.username");
		String password = configProps.getProperty("hw5.password");
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
	protected static Connection openConnectionFromCredential(String serverURL, String dbName, String adminName,
			String password) throws SQLException {
		String connectionUrl = String.format("jdbc:sqlserver://%s:1433;databaseName=%s;user=%s;password=%s", serverURL,
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
			// TODO: YOUR CODE HERE
			clear.execute();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/*
	 * prepare all the SQL statements in this method.
	 */
	private void prepareStatements() throws SQLException {
		checkFlightCapacityStatement = conn.prepareStatement(CHECK_FLIGHT_CAPACITY);
		tranCountStatement = conn.prepareStatement(TRANCOUNT_SQL);
		clear = conn.prepareStatement(CLEAR_TABLES_SQL);
		login = conn.prepareStatement(LOGIN_SQL);
		createUser = conn.prepareStatement(CREATE_SQL);
		directSearchSQL = conn.prepareStatement(SEARCH_DIRECT);
		oneHopSearchSQL = conn.prepareStatement(SEARCH_ONE_HOP);
		checkReservationDate = conn.prepareStatement(CHECK_RESERVATION_DATE);
		checkAvailability = conn.prepareStatement(CHECK_AVAILABILITY);
		bookItinerary = conn.prepareStatement(BOOK_ITINERARY);
		changeBalance = conn.prepareStatement(CHANGE_BALANCE);
		getReservationFromUser = conn.prepareStatement(GET_RESERVATION_FROM_USER);
		getReservation = conn.prepareStatement(GET_RESERVATION);
		getUser = conn.prepareStatement(GET_USER);
		changeBalanceReservation = conn.prepareStatement(CHANGE_BALANCE_RESERVATION);
		nextIdReservation = conn.prepareStatement(NEXT_ID_RES);
		getFlightInfo = conn.prepareStatement(GET_FLIGHT_INFO);
		deleteReservation = conn.prepareStatement(DELETE_RESERVATION);
		checkAvailabilityReservations1 = conn.prepareStatement(CHECK_AVAILABILITY_RESERVATIONS_1);
		checkAvailabilityReservations2 = conn.prepareStatement(CHECK_AVAILABILITY_RESERVATIONS_2);
	}

	/**
   * Takes a user's username and password and attempts to log the user in.
   *
   * @param username user's username
   * @param password user's password
   *
   * @return If someone has already logged in, then return "User already logged
   *         in\n" For all other errors, return "Login failed\n". Otherwise,
   *         return "Logged in as [username]\n".
   * @throws UnsupportedEncodingException
   */
  public String transaction_login(String username, String password) {
		try {
			conn.setAutoCommit(true);
			
			// TODO: YOUR CODE HERE
			login.setString(1, username);
			
			ResultSet rlogin = login.executeQuery();
			while (rlogin.next()) {
				String storedHash = rlogin.getString("hash");
				String storedSalt = rlogin.getString("salt");

        byte[] hash = getHash(password, storedSalt);
        Base64.Encoder enc = Base64.getEncoder();
        String encodedHash = enc.encodeToString(hash);

				if (storedHash.equals(encodedHash)) {
          if (currUser.equals("")) {
					  currUser = username;
            return "Logged in as " + username + "\n";
          }
          return "User already logged in\n";
        }
			}
			return "Login failed\n";
		} catch (SQLException ex) {
			throw new IllegalStateException("Login failed\n");
		} catch (UnsupportedEncodingException e) {
      throw new IllegalStateException("Login failed\n");
    } finally {
			checkDanglingTransaction();
		}
	}

	/**
	 * Implement the create user function.
	 *
	 * @param username   new user's username. User names are unique the system.
	 * @param password   new user's password.
	 * @param initAmount initial amount to deposit into the user's account, should
	 *                   be >= 0 (failure otherwise).
	 *
	 * @return either "Created user {@code username}\n" or "Failed to create user\n"
	 *         if failed.
	 */
	public String transaction_createCustomer(String username, String password, int initAmount) {
		if (initAmount < 0) {
			return "Failed to create user\n";
		}
		try {
			// TODO: YOUR CODE HERE
			// Generate a random cryptographic salt
			SecureRandom random = new SecureRandom();
			byte[] salt = new byte[16];
      random.nextBytes(salt);

      Base64.Encoder enc = Base64.getEncoder();
      String encodedSalt = enc.encodeToString(salt);

			byte[] hash = getHash(password, encodedSalt);
			String encodedHash = enc.encodeToString(hash);
			
			try {
				conn.setAutoCommit(true);
				conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
				
				createUser.setString(1, username);
				createUser.setString(2, encodedHash);
				createUser.setString(3, encodedSalt);
				createUser.setInt(4, initAmount);
				createUser.execute();
			} catch (SQLException y) {
				return "Failed to create user\n";
			}
			return "Created user " + username + "\n";
		  } catch (UnsupportedEncodingException e) {
        return "Failed to create user\n";
      } finally {
			  checkDanglingTransaction();
      }
  }

	private byte[] getHash(String password, String salt) throws UnsupportedEncodingException {

		// Specify the hash parameters
		KeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(), HASH_STRENGTH, KEY_LENGTH);

		// Generate the hash
		SecretKeyFactory factory = null;
		byte[] hash = null;
		try {
			factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
      hash = factory.generateSecret(spec).getEncoded();
      return hash;
		} catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
			throw new IllegalStateException();
		}
	}


/**
	 * Implement the search function.
	 *
	 * Searches for flights from the given origin city to the given destination
	 * city, on the given day of the month. If {@code directFlight} is true, it only
	 * searches for direct flights, otherwise is searches for direct flights and
	 * flights with two "hops." Only searches for up to the number of itineraries
	 * given by {@code numberOfItineraries}.
	 *
	 * The results are sorted based on total flight time.
	 *
	 * @param originCity
	 * @param destinationCity
	 * @param directFlight        if true, then only search for direct flights,
	 *                            otherwise include indirect flights as well
	 * @param dayOfMonth
	 * @param numberOfItineraries number of itineraries to return
	 *
	 * @return If no itineraries were found, return "No flights match your
	 *         selection\n". If an error occurs, then return "Failed to search\n".
	 *
	 *         Otherwise, the sorted itineraries printed in the following format:
	 *
	 *         Itinerary [itinerary number]: [number of flights] flight(s), [total
	 *         flight time] minutes\n [first flight in itinerary]\n ... [last flight
	 *         in itinerary]\n
	 *
	 *         Each flight should be printed using the same format as in the
	 *         {@code Flight} class. Itinerary numbers in each search should always
	 *         start from 0 and increase by 1.
	 *
	 * @see Flight#toString()
	 */
	public String transaction_search(String originCity, String destinationCity, boolean directFlight, int dayOfMonth,
			int numberOfItineraries) {
		lastRS = new ArrayList();
		if (directFlight) {
			directSearch(originCity, destinationCity, dayOfMonth, numberOfItineraries);
		} else {
			oneHopSearch(originCity, destinationCity, dayOfMonth, numberOfItineraries);
			orderItineraries();
		}
		String w = returnSearch();
		if(w.equals("")) {
			return "No flights match your selection\n";
		} else {
			return returnSearch();
		}
	}
	
	//returns the contents of the lastRS arrayList
	public String returnSearch() {
		String t = "";
		for(int i = 0; i < lastRS.size(); i++) {
			Itinerary w = lastRS.get(i);
			t = t + "Itinerary " + i + ": " + w.numFlights + " flight(s), " + w.time + " minutes\n";
			t = t + lastRS.get(i).toString() + "\n";
		}
	return t;
	}
	
	public void directSearch(String originCity, String destinationCity, int dayOfMonth, int numberOfItineraries) {
		try {
			// TODO: YOUR CODE HERE

			int itinerariesFound = 0;
			StringBuffer sb = new StringBuffer();
			try {
				conn.setAutoCommit(true);
				conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
				
				// direct itineraries
				directSearchSQL.setInt(1, numberOfItineraries);
				directSearchSQL.setString(2, originCity);
				directSearchSQL.setString(3, destinationCity);
				directSearchSQL.setInt(4, dayOfMonth);

				ResultSet results = directSearchSQL.executeQuery();

				while (results.next() && itinerariesFound < numberOfItineraries) {
					int result_fid = results.getInt("fid");
					int result_dayOfMonth = results.getInt("day_of_month");
					String result_carrierId = results.getString("carrier_id");
					String result_flightNum = results.getString("flight_num");
					String result_originCity = results.getString("origin_city");
					String result_destCity = results.getString("dest_city");
					int result_time = results.getInt("actual_time");
					int result_capacity = results.getInt("capacity");
					int result_price = results.getInt("price");
		
					Flight f = new Flight(result_fid, result_dayOfMonth, result_carrierId, result_flightNum, result_originCity, result_destCity,
							result_time, result_capacity, result_price);
					Itinerary i = new Itinerary(f);
					lastRS.add(i);
				}
				results.close();
			} catch (SQLException e) {
				throw new IllegalArgumentException();
			}
		} finally {
			checkDanglingTransaction();
		}
	}
	
	public void oneHopSearch(String originCity, String destinationCity, int dayOfMonth, int numberOfItineraries) {
		directSearch(originCity, destinationCity, dayOfMonth, numberOfItineraries);
		if(!(lastRS.size() >= numberOfItineraries)) { //if there aren't enough direct flights
			StringBuffer sb = new StringBuffer();
			try {
				conn.setAutoCommit(true);
				conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
				
				// one hop itineraries
				oneHopSearchSQL.clearParameters();
				oneHopSearchSQL.setInt(1, numberOfItineraries - lastRS.size());
				oneHopSearchSQL.setString(2, originCity);
				oneHopSearchSQL.setString(3, destinationCity);
				oneHopSearchSQL.setInt(4, dayOfMonth);

				ResultSet results = oneHopSearchSQL.executeQuery();
				while (results.next()) {
					int result_f1_fid = results.getInt("f1_id");
					int result_f1_dayOfMonth = results.getInt("f1_day_of_month");
					String result_f1_carrierId = results.getString("f1_carrier_id");
					String result_f1_flightNum = results.getString("f1_flight_num");
					int result_f1_time = results.getInt("f1_time");
					int result_f1_capacity = results.getInt("f1_capacity");
					int result_f1_price = results.getInt("f1_price");
					String result_f1_originCity = results.getString("f1_origin_city");
					String result_f1_destCity = results.getString("f1_dest_city");
					
					int result_f2_fid = results.getInt("f2_id");
					int result_f2_dayOfMonth = results.getInt("f2_day_of_month");
					String result_f2_carrierId = results.getString("f2_carrier_id");
					String result_f2_flightNum = results.getString("f2_flight_num");
					int result_f2_time = results.getInt("f2_time");
					int result_f2_capacity = results.getInt("f2_capacity");
					int result_f2_price = results.getInt("f2_price");
					String result_f2_originCity = results.getString("f2_origin_city");
					String result_f2_destCity = results.getString("f2_dest_city");
					
					Flight f1 = new Flight(result_f1_fid, result_f1_dayOfMonth, result_f1_carrierId, result_f1_flightNum, result_f1_originCity, result_f1_destCity,
							result_f1_time, result_f1_capacity, result_f1_price);
					Flight f2 = new Flight(result_f2_fid, result_f2_dayOfMonth, result_f2_carrierId, result_f2_flightNum, result_f2_originCity, result_f2_destCity,
							result_f2_time, result_f2_capacity, result_f2_price);
					Itinerary i = new Itinerary(f1, f2);
					lastRS.add(i);	
				}
				results.close();
				} catch (SQLException e) {
					e.printStackTrace();
				}
			 finally {
				checkDanglingTransaction();
			}
		}
	}

	
	//orders results by time, then first fid, then second fid
	public void orderItineraries() {
		Collections.sort(lastRS, Comparator.comparing(Itinerary::getTime)
	            .thenComparing(Itinerary::getF1ID)
	            .thenComparing(Itinerary::getF2ID));
	}

	
	/**
	 * Implements the book itinerary function.
	 *
	 * @param itineraryId ID of the itinerary to book. This must be one that is
	 *                    returned by search in the current session.
	 *
	 * @return If the user is not logged in, then return "Cannot book reservations,
	 *         not logged in\n". If the user is trying to book an itinerary with an
	 *         invalid ID or without having done a search, then return "No such
	 *         itinerary {@code itineraryId}\n". If the user already has a
	 *         reservation on the same day as the one that they are trying to book
	 *         now, then return "You cannot book two flights in the same day\n". For
	 *         all other errors, return "Booking failed\n".
	 *
	 *         And if booking succeeded, return "Booked flight(s), reservation ID:
	 *         [reservationId]\n" where reservationId is a unique number in the
	 *         reservation system that starts from 1 and increments by 1 each time a
	 *         successful reservation is made by any user in the system.
	 */
	public String transaction_book(int itineraryId) {
		int rid = -1;
		if (currUser == "") { // not logged in
			return "Cannot book reservations, not logged in\n";
		} else if(lastRS == null) {
			return "No such itinerary " + itineraryId + "\n";
		} else if (itineraryId > lastRS.size() || itineraryId < 0 || lastRS.isEmpty()) { // id out of scope or negative
			return "No such itinerary " + itineraryId + "\n";
		}
		try {
			Itinerary i = lastRS.get(itineraryId);

			// check if user already has a reservation on that day
			try {
				conn.setAutoCommit(true);
				conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
				checkReservationDate.setString(1, currUser);
				checkReservationDate.setInt(2, i.flight1.dayOfMonth);
				ResultSet rs = checkReservationDate.executeQuery();
				rs.next();
				if (Integer.valueOf(rs.getInt("sum")) > 0) {
					return "You cannot book two flights in the same day\n";
				}
				rs.close();
			} catch (SQLException l) {
				l.printStackTrace();
			}
		try {	
			
			// check availability
			int id1 = i.f1id;
			int id2 = i.f1id;
			if (i.numFlights == 2) { // by default, both ids are set to the first flight, if its one-hop, the second id becomes the id of the second flight
				id2 = i.f2id;
			}
			int capacity1 = 0;
			int capacity2 = 10; //in case it doesn't have a second flight, the code thinks there's space and doesn't freak out
			int reservations1 = 0;
			int reservations2 = 9;
			
				conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
				conn.setAutoCommit(false);
				
				
				// check reservations table for capacity
				checkAvailabilityReservations1.clearParameters();
				checkAvailabilityReservations1.setInt(1, id1);
				ResultSet rs2 = checkAvailabilityReservations1.executeQuery();
				rs2.next();
				reservations1 = rs2.getInt("count_rid");		
				if(i.numFlights == 2) {
					checkAvailabilityReservations2.clearParameters();
					checkAvailabilityReservations2.setInt(1, id2);
					ResultSet rs3 = checkAvailabilityReservations2.executeQuery();
					rs3.next();
					reservations2 = rs3.getInt("count_rid");
					rs3.close();
				}
				rs2.close();
				
				
				// check flights table for capacity
				checkAvailability.setInt(1, id1);
				checkAvailability.setInt(2, id2);
				ResultSet rs = checkAvailability.executeQuery();
				int count = 0;
				while(rs.next()) {
					if(count == 0) {
						capacity1 = Integer.valueOf(rs.getInt("capacity"));
					} else {
						capacity2 = Integer.valueOf(rs.getInt("capacity"));
					}
					count++;
				}
				rs.close();
				
						
				if(capacity1-reservations1 <= 0 || capacity2-reservations2 <= 0) {
					conn.rollback();
					conn.setAutoCommit(true);
					return "Booking failed\n";
				}
			
			// all criteria met by here, books the flight
			// add to reservations table
				rid = nextIDReservations();
				bookItinerary.clearParameters();
				bookItinerary.setInt(1, rid);
				bookItinerary.setString(2, currUser);
				bookItinerary.setString(3, i.flight1.originCity);
				bookItinerary.setInt(5, i.flight1.dayOfMonth);
				bookItinerary.setInt(6, i.f1id);
				if(i.numFlights == 1) {
					bookItinerary.setString(4, i.flight1.destCity);
					bookItinerary.setNull(7, Types.INTEGER);
				} else {
					bookItinerary.setString(4, i.flight2.destCity);
					bookItinerary.setInt(7, i.f2id);
				}
				bookItinerary.setInt(8, 0);
				bookItinerary.setInt(9, i.price);
				bookItinerary.execute();
				
				conn.commit();
				conn.setAutoCommit(true);
				return "Booked flight(s), reservation ID: " + rid + "\n";
				
			}	catch (SQLException w) {
				try {
					conn.rollback();
					conn.setAutoCommit(true);
					return "Booking failed\n";
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
			return "Booking failed\n";
		} finally {
			checkDanglingTransaction();
		}
	}

	/**
	 * Implements the pay function.
	 *
	 * @param reservationId the reservation to pay for.
	 *
	 * @return If no user has logged in, then return "Cannot pay, not logged in\n"
	 *         If the reservation is not found / not under the logged in user's
	 *         name, then return "Cannot find unpaid reservation [reservationId]
	 *         under user: [username]\n" If the user does not have enough money in
	 *         their account, then return "User has only [balance] in account but
	 *         itinerary costs [cost]\n" For all other errors, return "Failed to pay
	 *         for reservation [reservationId]\n"
	 *
	 *         If successful, return "Paid reservation: [reservationId] remaining
	 *         balance: [balance]\n" where [balance] is the remaining balance in the
	 *         user's account.
	 */
	public String transaction_pay(int reservationId) {
		if(currUser.equals("")) {
			return "Cannot pay, not logged in\n";
		}	
		try {
			// get current balance
			int moneyInAccount = 0;
			int price = 0;
			try {
				getUser.setString(1, currUser);
				ResultSet rs = getUser.executeQuery();
				while(rs.next()) {
					moneyInAccount = rs.getInt("balance");
				}
				rs.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
			
			// get reservation info
			try {
				getReservation.setInt(1, reservationId);
				getReservation.setString(2, currUser);
				ResultSet rs = getReservation.executeQuery();
				if(rs.next() == false) { // no reservations returned by query
					return "Cannot find unpaid reservation " + reservationId + " under user: " + currUser + "\n";
				} 
				price = rs.getInt("amtUnpaid");
				String resUser = rs.getString("username");
				if(!(resUser.equalsIgnoreCase(currUser))) { // reservation booked by another user
					return "Cannot find unpaid reservation " + reservationId + " under user: " + currUser + "\n";
				} if (moneyInAccount < price) { // user doesn't have enough money
					return "User has only " + moneyInAccount + " in account but itinerary costs " + price + "\n";
				} if (price <= 0) { // already paid
					return "Cannot find unpaid reservation " + reservationId + " under user: " + currUser + "\n";
				}
				rs.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
			
			// take money out of account
			try {
				changeBalance.clearParameters();
				changeBalance.setInt(1, price);
				changeBalance.setString(2, currUser);
				changeBalance.execute();
			} catch (SQLException e) {
				e.printStackTrace();
			}
			
			// record payment in reservations table
			try {
				changeBalanceReservation.setInt(1, price);
				changeBalanceReservation.setInt(2, price);
				changeBalanceReservation.setInt(3, reservationId);
				changeBalanceReservation.execute();
			} catch (SQLException e) {
				e.printStackTrace();
			}
			return "Paid reservation: " + reservationId + " remaining balance: " + (moneyInAccount - price) + "\n";
		} finally {
			checkDanglingTransaction();
		}
	}

	/**
	 * Implements the reservations function.
	 *
	 * @return If no user has logged in, then return "Cannot view reservations, not
	 *         logged in\n" If the user has no reservations, then return "No
	 *         reservations found\n" For all other errors, return "Failed to
	 *         retrieve reservations\n"
	 *
	 *         Otherwise return the reservations in the following format:
	 *
	 *         Reservation [reservation ID] paid: [true or false]:\n [flight 1 under
	 *         the reservation]\n [flight 2 under the reservation]\n Reservation
	 *         [reservation ID] paid: [true or false]:\n [flight 1 under the
	 *         reservation]\n [flight 2 under the reservation]\n ...
	 *
	 *         Each flight should be printed using the same format as in the
	 *         {@code Flight} class.
	 *
	 * @see Flight#toString()
	 */
	public String transaction_reservations() {
		if(currUser.equals("")) {
			return "Cannot view reservations, not logged in\n";
		}
		try {
			try {
				getReservationFromUser.clearParameters();
				getReservationFromUser.setString(1, currUser);
				ResultSet rs = getReservationFromUser.executeQuery();
				StringBuffer sb = new StringBuffer();
				while (rs.next()) {
					int rid = rs.getInt("rid");
					int numFlights = 2;
					Itinerary i;
					if(rs.getInt("f2_id") == 0) {
						numFlights = 1;
					}
					String paid = "false";
					if(rs.getInt("amtUnpaid") == 0) {
						paid = "true";
					}
					if(numFlights == 1) {
						i = new Itinerary(getFlightInfoFromDB(rs.getInt("f1_id")));
					} else {
						i = new Itinerary(getFlightInfoFromDB(rs.getInt("f1_id")), getFlightInfoFromDB(rs.getInt("f2_id")));
					}
					sb.append("Reservation " + rid + " paid: " + paid + ":\n" + i.toString() + "\n");
				}
				rs.close();
				if(sb.equals(new StringBuffer())) {
					return "No reservations found\n";
				}
				return sb.toString();
			} catch (SQLException e) {
				e.printStackTrace();
			}
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
	 * @return If no user has logged in, then return "Cannot cancel reservations,
	 *         not logged in\n" For all other errors, return "Failed to cancel
	 *         reservation [reservationId]\n"
	 *
	 *         If successful, return "Canceled reservation [reservationId]\n"
	 *
	 *         Even though a reservation has been canceled, its ID should not be
	 *         reused by the system.
	 */
	public String transaction_cancel(int reservationId) {
		if(currUser.equals("")) {
			return "Cannot cancel reservations, not logged in\n";
		}
		try {
			try {
				getReservation.clearParameters();
				getReservation.setInt(1, reservationId);
				getReservation.setString(2, currUser);
				ResultSet rs = getReservation.executeQuery();
				if(!rs.next()) {
					return "Failed to cancel reservation " + reservationId + "\n";
				}
				int refund = 0;
				if(rs.getInt("amtPaid") > 0) {
					refund = rs.getInt("amtPaid");
				}
			
				// refund the money
				changeBalance.clearParameters();
				changeBalance.setInt(1, (int) (-1 * refund));
				changeBalance.setString(2, currUser);
				changeBalance.execute();
			
				// delete the reservation
				deleteReservation.clearParameters();
				deleteReservation.setInt(1, reservationId);
				deleteReservation.setString(2, currUser);
				deleteReservation.execute();
			
				rs.close();
				return "Canceled reservation " + reservationId + "\n";
			} catch (SQLException e) {
				return e.toString();
			}
		} finally {
			checkDanglingTransaction();
		}
	}
	
	public Flight getFlightInfoFromDB(int fid) throws SQLException {
		getFlightInfo.clearParameters();
		getFlightInfo.setInt(1, fid);
		ResultSet rs = getFlightInfo.executeQuery();
		if(!rs.next()) {
			return null;
		}
		Flight f = new Flight(rs.getInt("fid"), rs.getInt("day_of_month"), rs.getString("carrier_id"), rs.getString("flight_num"), rs.getString("origin_city"),
				rs.getString("dest_city"), rs.getInt("actual_time"), rs.getInt("capacity"), rs.getInt("price"));
		rs.close();
		return f;
	}
	
	public int nextIDReservations() throws SQLException {
		nextIdReservation.clearParameters();
		ResultSet rs = nextIdReservation.executeQuery();
		if(!rs.next()) {
			return 1;
		}
		int max_rid = rs.getInt("max_rid");
		rs.close();
		return (max_rid+1);
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
	
	
	class Itinerary {
		public Flight flight1;
		public Flight flight2;
		public int f1id;
		public int f2id;
		public int time;
		public int price;
		public int numFlights;
		
		public Itinerary(Flight f) {
			this.flight1 = f;
			this.time = flight1.time;
			this.numFlights = 1;
			this.f1id = f.fid;
			this.price = f.price;
		}
		public Itinerary(Flight f1, Flight f2) {
			this.flight1 = f1;
			this.flight2 = f2;
			this.time = (f1.time + f2.time);
			this.numFlights = 2;
			this.f1id = f1.fid;
			this.f2id = f2.fid;
			this.price = f1.price + f2.price;
		}
		
		public int getTime() {
			return this.time;
		}
		public int getF1ID() {
			return this.f1id;
		}
		public int getF2ID() {
			return this.f2id;
		}
		
		public String toString() {
			if(!(flight2 == null)) {
				return flight1.toString() + "\n" + flight2.toString();
			} else {
				return flight1.toString();
			}
		}
	}
	
	
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

		
		public Flight(int fid, int dayOfMonth, String carrierId, String flightNum, String originCity, String destCity, int time, int capacity, int price) {
			this.fid = fid;
			this.dayOfMonth = dayOfMonth;
			this.carrierId = carrierId;
			this.flightNum = flightNum;
			this.originCity = originCity;
			this.destCity = destCity;
			this.time = time;
			this.capacity = capacity;
			this.price = price;
			
		}
		
		public String toString() {
			return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId + " Number: " + flightNum
					+ " Origin: " + originCity + " Dest: " + destCity + " Duration: " + time + " Capacity: " + capacity
					+ " Price: " + price;
		}
	}
}
