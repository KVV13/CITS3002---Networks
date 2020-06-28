import java.util.*;
import java.net.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.*;
import java.text.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.util.Timer;

/**
* Description of project
*
* @author (Kushil Vaghjiani (22503488))
* @version (Version: Final)
*/

public class station{
	private String server_name = "";													// Name of station/server
	private String tcp_time = "";														// Time request made
	private String global_dest = "";													// Destination to reach
	private int tcp_port;																// TCP port in use
	private int udp_port;																// UDP port in use
	private ServerSocketChannel tcpserver;												// Handles TCP
	private DatagramChannel udpserver;													// Handles UDP
	private boolean search_sent = false;												// Whether adjacent requests are sent
	private String[] inputs; 															// Inputs from command line 								
	private int inputs_len;																// Number of inputs
	private SimpleDateFormat format = new SimpleDateFormat("HH:mm"); 					// Format of time variables
	private ArrayList<String> tt_departure_time = new ArrayList<String>(); 				// Timetable departure time
	private ArrayList<String> tt_line = new ArrayList<String>(); 						// Timetable leaving line
	private ArrayList<String> tt_departure_station = new ArrayList<String>(); 			// Timetable departure station
	private ArrayList<String> tt_arrival_time = new ArrayList<String>(); 				// Timetable arrival at dest
	private ArrayList<String> tt_destination= new ArrayList<String>(); 					// Timetable destination
	private ArrayList<String> adjacent_st_name = new ArrayList<String>();				// Adjacent station names
	private ArrayList<Integer> adjacent_ports = new ArrayList<Integer>();				// Adjacent ports
	private long last_edit = 0;															// Last edit of timetable
	private String fastest_arrival = null; 												// Fastest arriving route at destination
	private SocketChannel[] tcp_connection = new SocketChannel[1];						// Tracks current TCP connection
	private boolean timer_started = false;												// Whether timer is on/off
	private Timer timer;																// Timer to shut TCP connection
	private Queue<String> msg_to_send = new LinkedList<String>();						// Handles messages to send via UDP
	private Queue<Integer> port_to_send = new LinkedList<Integer>();					// And corresponding ports to send to 
	private String[] final_tcp_return = new String[1];									// Final message to return via TCP


	public station(String[] inputs){	// Initialise certain variables
		this.inputs = inputs;
		inputs_len = inputs.length;
		server_name = inputs[0];
		tcp_port = Integer.parseInt(inputs[1]);
		udp_port = Integer.parseInt(inputs[2]);
	}

	// This function generates a data structure to hold and read timetable information
	public void read_timetable(){

		try{
			String timetable_file = "tt-" + server_name;						// Name of server timetable file
			File timetable_edit = new File(timetable_file);
			long last_mod = timetable_edit.lastModified();						// Checks timetables last modification time

			if(last_edit == 0 || last_edit != last_mod){						// Ensures timetable isn't read unnecessarily
				tt_departure_time.clear();
				tt_line.clear();
				tt_departure_station.clear();
				tt_arrival_time.clear();
				tt_destination.clear();
				BufferedReader csvReader = new BufferedReader(new FileReader(timetable_file));
				String row;
				int skiprow = 0;

				while((row = csvReader.readLine()) != null){					// Read file line by line adding data to data structures
					skiprow++;

					if(skiprow == 1){ 											// Skip header of file
						continue;
					}

					String[] data = row.split(",");								// Split data into its components
					int check_dep_time = Integer.parseInt(data[0].split(":")[0]);
					int check_arr_time = Integer.parseInt(data[3].split(":")[0]);

					if(check_dep_time >= 24 || check_arr_time >= 24){ 			// Do not allow times with hour 24 to be appended
						continue;
					}

					tt_departure_time.add(data[0]);
					tt_line.add(data[1]);
					tt_departure_station.add(data[2]);
					tt_arrival_time.add(data[3]);
					tt_destination.add(data[4]);
				}

				csvReader.close();
				last_edit = last_mod; 											// Update last modification time
			}

		}
		catch(IOException e){
			System.out.println("No such file exists");
		}
	}

	// This function generates routes to adjacent stations and returns relevent info
	public String[] generate_route(String request_time, String dest){
		String destination = dest;												// Destination to reach
		Date depart_time = null;												// Hold different departure times for calcs
		Date departure_time = null;
		Date arrival = null;
		Date arrival_time = null;
		long prev_time_diff = 0;
		long time_diff = 0;
		String prev_line = null;
		String line = null;
		String platform = null;
		String prev_platform = null;
		String[] return_values = new String[4];
		
		try{
			Date req_time = format.parse(request_time);							// Time request is made
			int timetable_rows = tt_departure_time.size();						// Number of rows in timetable

			for(int i=0; i<timetable_rows;i++){

				if(!(tt_destination.get(i).equals(destination))){				// If destination isn't selected 
					continue;
				}

				if((tt_destination.get(i).equals(destination))){
					departure_time = format.parse(tt_departure_time.get(i));	// Get departure time
					arrival_time = format.parse(tt_arrival_time.get(i));		// And arrival time
					time_diff = departure_time.getTime() - req_time.getTime();	// Find their difference to ensure positive difference
					time_diff = time_diff/1000/60; 								// Time difference in minutes
					prev_line = tt_line.get(i);									
					prev_platform = tt_departure_station.get(i);
				}

				if(time_diff<0){												// Cannot have a negative time
					continue;
				}

				else if(depart_time == null){ 									// No departure time found
					depart_time = departure_time;
					prev_time_diff = time_diff;
					arrival = arrival_time;
					line = prev_line;
					platform = prev_platform;
				}

				else if(time_diff<prev_time_diff){ 								// Shorter time found
					depart_time = departure_time;
					prev_time_diff = time_diff;
					arrival = arrival_time;
					line = prev_line;
					platform = prev_platform;
				}

				else if(time_diff>prev_time_diff){								// Time is greater, therefore break loop
					break;
				}

			}

			if(depart_time == null || arrival == null){							// No routes today
				return_values[0] = "NO_ROUTE";
				return_values[1] = "FAILED";
				return_values[2] = "FAILED";
				return_values[3] = "FAILED";
				return return_values;
			}

			// Return values for route if they exist
			return_values[0] = (String) format.format(arrival);					
			return_values[1] = line;
			return_values[2] = (String) format.format(depart_time);
			return_values[3] = platform;
		}
		catch(ParseException p){
			System.out.println(p + "in find_route");
		}

		return return_values;
	}

	// This function handles initial incoming TCP connection
	public boolean handle_tcp(String request){

		if(request.equals("")){
			return false;
		}

		String incoming_request = null;
		String return_string = null;
		String destination_request = null;												// Destination to reach
		ByteBuffer tcp_buffer = ByteBuffer.allocate(1024);								// Buffer for TCP connection

		try{

			if(request.contains("GET")){												// Only accept requests with "GET"
				incoming_request = request.split(" ")[1];
			}

			if(incoming_request.equals("/favicon.ico") || incoming_request==null){		// Ignore these requests
				return_string = ("HTTP/1.1 400 Bad Request\n" +
	                			"Connection: close\n");// 

				tcp_buffer.clear();
				tcp_buffer.put(return_string.getBytes());
				tcp_buffer.flip(); 														// Usually called after put!

				while(tcp_buffer.hasRemaining()){										// Send TCP back to server
					tcp_connection[0].write(tcp_buffer);
				}

				tcp_connection[0].close();
				tcp_connection = new SocketChannel[1];
				return false;
			}

			destination_request = incoming_request.split("=")[1];						// Get destination request

			if(!(destination_request.equals("="))){
				Date date = new Date();
				//TCP_TIME!
				tcp_time = (String) format.format(date);
				//tcp_time = "16:00";
				global_dest = destination_request;										// Set destination to reach
			}

			return true;
		}
		catch(Exception e){
			System.out.println(e + "in handle TCP Request");
		}

		return true;
	}

	// This function initialises server and information
	public void udp_initialise(String data){
		int station_source;							// Where data is coming from
		String station_from;						// Name of station from
		String msg;									// Message to send back
		String outgoing = data.split(",")[0];		// Categorise data to back out
		String req_station;							// Requested station name
		int req_source;								// Requested station port

		if(outgoing.equals("REQUEST_STATION")){
			station_source = Integer.parseInt(data.split(",")[1].split("=")[1].split(" ")[1]); 					// AVOID EMPTY SPACES!!
			station_from = data.split(",")[2].split("=")[1].split(" ")[1];												
			msg = ("REQUEST_STATION_DETAILS = " + server_name + ", SOURCE = " + Integer.toString(udp_port));	// Message to return back

			msg_to_send.add(msg);								// Add to queue to send back
			port_to_send.add(station_source);					// Add port corresponding too

			if(!(adjacent_ports.contains(station_source))){		// If station_source isn't in this servers directory
				adjacent_ports.add(station_source);
				adjacent_st_name.add(station_from);
			}

			if(search_sent == false){							// Send packets to adjacent nodes

				for(int i=3; i<inputs_len; i++){
					int adj_port = Integer.parseInt(inputs[i]);

					if(adjacent_ports.contains(adj_port) || adj_port == station_source){ 	// Don't send back to known address
						continue;
					}

					msg = ("REQUEST_STATION, SOURCE = " + Integer.toString(udp_port) + ", FROM = " + server_name);
					msg_to_send.add(msg);
					port_to_send.add(adj_port);
				}

			}

			search_sent = true;		// Packets sent to adjacent nodes
		}

		else if(outgoing.contains("REQUEST_STATION_DETAILS")){		// Process adjacent stations data
			req_station = data.split(",")[0].split("=")[1].split(" ")[1];
			req_source = Integer.parseInt(data.split(",")[1].split("=")[1].split(" ")[1]);

			if(adjacent_ports.contains(req_source)){ 	// If station already found
				return;
			}

			adjacent_st_name.add(req_station);
			adjacent_ports.add(req_source);
		}

		return;
	}

	// This function sends out search packets for required station
	public void udp_search(String packet){
		String data = packet.replaceAll("\\s+","");								// Remove empty spaces in string
		String[] process_data = data.split(";");								// Split data into components
		String destination = process_data[0].split("=")[1];						// Destination to reach
		String time_node_arrival = process_data[1].split("=")[1];				// Time arriving at this node
		String visited = process_data[2].split("=")[1];							// List of visited nodes
		int ttl_pack = Integer.parseInt(process_data[3].split("=")[1]);			// TTL counter
		String leaving_time = process_data[4].split("=")[1];					// Time left origin
		String leaving_line = process_data[5].split("=")[1];					// Line from origin
		String leaving_platform = process_data[6].split("=")[1];				// Platform from origin
		String[] port = visited.split(",");										// Visited ports split
		int visited_ports = port.length;										// Number of visited ports
		String[] route_details;													// Route details for next hop
		String found_packet = null;												// Holds found packet
		String search_packet;													// Holds search packet

		if(visited.contains(Integer.toString(udp_port)) || ttl_pack==25){ 		// Looped, or too many hops
			return; 															// Terminate packet
		}

		read_timetable();														// Generate timetable

		//////////////////////////////////////////////// If node is adjacent ///////////////////////////////////////////
		int num_adj_stations = adjacent_ports.size();

		for(int i=0; i<num_adj_stations; i++){										//Check adjacent stations first

			if(adjacent_st_name.get(i).equals(destination)){
				route_details = generate_route(time_node_arrival, destination);		// Check adjacent station leaving time

				if(!(route_details[0].equals("NO_ROUTE"))){	// Route found
					found_packet = "Found "+ "; Visited = " + visited + "; Arrival = " + route_details[0] + "; Origin_time = " + leaving_time + 
                		"; Origin_line = " + leaving_line + "; Dest =" + destination + "; Platform =" + leaving_platform;
				}

				else if(route_details[0].equals("NO_ROUTE")){ // No route found
					found_packet = "NO_ROUTE_TODAY"+ "; Visited = " + visited + "; Arrival = " + route_details[0] + "; Origin_time = " + leaving_time + 
						"; Origin_line = " + leaving_line + "; Dest =" + destination + "; Platform =" + leaving_platform;
				}
				
				int prev_port = Integer.parseInt(port[visited_ports-1]);
				msg_to_send.add(found_packet);
				port_to_send.add(prev_port);

				if(!(route_details[0].equals("NO_ROUTE"))){	// Search continue if no_route to destination
					return;
				}

			}

		}

		////////////////////////////////////////////// Add current node to visited ///////////////////////////////////

		int receivefrom = Integer.parseInt(port[visited_ports-1]);		// Station received from
		visited = visited + "," + Integer.toString(udp_port);			// Add this station to visited list

		////////////////////////////////////////////// Node isn't adjacent ///////////////////////////////////////////

		ttl_pack++;		// Increase TTL counter

		for(int i=0; i<num_adj_stations; i++){
			int adj_node = adjacent_ports.get(i);

			if(adj_node == receivefrom || visited.contains(Integer.toString(adj_node)) || adjacent_st_name.get(i).equals(destination)){ 
				continue;	//Don't send back to same node received from, or already visited node.
			}

			route_details = generate_route(time_node_arrival, adjacent_st_name.get(i));	// Generate next hop information
			
			search_packet = "Destination = " + destination + "; Time_Arrived = " + route_details[0] + "; Visited = " + visited + "; TTL = " 
						+ Integer.toString(ttl_pack) + "; Leave_Time = " + leaving_time + "; Line = " + leaving_line + "; Platform = " + leaving_platform;

			if(route_details[0].equals("NO_ROUTE")){	// If no route is found
				search_packet = "NO_ROUTE_TODAY" + "; Visited = " + visited + "; Arrival = " + route_details[0] + "; Origin_time = " + leaving_time + 
                    "; Origin_line = " + leaving_line + "; Dest =" + destination + "; Platform =" + leaving_platform;
			}

			msg_to_send.add(search_packet);
			port_to_send.add(adjacent_ports.get(i));
		}
	}

	// This function handles returning packet to original node
	public void return_packet(String packet){
		String data = packet.replaceAll("\\s+","");					// Remove empty spaces
		String[] process_data = data.split(";");					// Data split up
		String status = process_data[0];							// If route found or not
		String arrive_time = process_data[2].split("=")[1];			// Arrival time info
		String leave_time = process_data[3].split("=")[1];			// Origin leave time
		String leave_line = process_data[4].split("=")[1];			// Origin leave line
		String destination = process_data[5].split("=")[1];			// Destination searched from origin
		String leaving_platform = process_data[6].split("=")[1];	// Origin leaving platform
		List<String> route;											// Return route
		String route_outcome = null;								// Outcome of path taken
		String found_packet;										// Packet to continue back to origin node

		try{
		route = new ArrayList<String>(Arrays.asList(process_data[1].split("=")[1].split(",")));	// Try finding next return node
		}
		catch(ArrayIndexOutOfBoundsException e){	// If this is the origin node

			if(!(destination.equals(global_dest))){	// If this is not the destination
				return;
			}

			String outcome = ("ROUTE FOUND from " + server_name + " to destination " + global_dest + " leaving on platform " + leaving_platform + 
			" at " + leave_time + " on line: " + leave_line + ", arriving at " + arrive_time);
			
			if(!(status.equals("NO_ROUTE_TODAY")) && !(arrive_time.equals("NO_ROUTE"))){ // If route exists
				
				if(fastest_arrival==null || fastest_arrival.equals("NO_ROUTE_TODAY")){	// Found a route
					fastest_arrival = arrive_time;	// Updating values
					route_outcome = outcome;
					final_tcp_return[0] = route_outcome;
				}

				else if(!(fastest_arrival.equals("NO_ROUTE_TODAY"))){	// Compare new route to old

					try{
						Date old_arrival = format.parse(fastest_arrival);
						Date new_arrival_time = format.parse(arrive_time);

						if(new_arrival_time.before(old_arrival)){	// Found a faster route
							fastest_arrival = arrive_time;
							route_outcome = outcome;
							final_tcp_return[0] = route_outcome;	// Current fastest route
						}
					}
					catch(ParseException p){
						System.out.println(p);
					}

				}

			}

			else{	// No route found

				if(fastest_arrival==null){	// If fastest route isn't initialised
					fastest_arrival = "NO_ROUTE_TODAY";
					route_outcome = "No route today.";
					final_tcp_return[0] = route_outcome;
				}

			}

			if(route_outcome!=null){
			
				if(tcp_connection[0] == null){ 	// Connection is closed
					return;
				}

				if(timer_started == false){		// Check if TCP timer is on or not
					timer = new Timer();
					timer.schedule(new shut_tcp(), 5000);	//  Shut TCP connection after 5 seconds
					timer_started = true;
				}

			}

			return; //Route found and stored
		}

		int route_size = route.size();
		int next_dest = Integer.parseInt(route.get(route_size-1));
		route.remove(route_size-1);
		String ret_route = String.join(",", route); 	// Return route

		if(status.equals("NO_ROUTE_TODAY")){
			found_packet = "NO_ROUTE_TODAY"+ "; Visited = " + ret_route + "; Arrival = " + arrive_time + "; Origin_time = " + leave_time +
        "; Origin_line = " + leave_line + "; Dest =" + destination + "; Platform =" + leaving_platform;
		}

		else{
			found_packet = "Found "+ "; Visited = " + ret_route + "; Arrival = " + arrive_time + "; Origin_time = " + leave_time +
        "; Origin_line = " + leave_line + "; Dest =" + destination + "; Platform =" + leaving_platform;
		}
		
		msg_to_send.add(found_packet);	// Packets to send back to origin
		port_to_send.add(next_dest);
	}
	
	// This class shuts TCP connection
	class shut_tcp extends TimerTask{

		public void run(){
			ByteBuffer tcp_buffer = ByteBuffer.allocate(1024);

			if(tcp_connection[0] != null){									// If connection still exists

				if(final_tcp_return[0] == null){							// If nothing returned from searches
					final_tcp_return[0] = "No route found";
				}

				try{
					String send_pack = ("HTTP/1.1 200 OK\n" +				// HTML sent back to browser
	                        "Content-Type: text/html\n" +
	                        "Connection: keep-alive\n" +
	                        "\n" +
	                        "<html>\n" +
	                        "<body>\n" +
	                        "<h1>" + final_tcp_return[0] +"</h1>\n" +
	                        "</body>\n" +
		                    "</html>\n");

					tcp_buffer.clear();
					tcp_buffer.put(send_pack.getBytes());
					tcp_buffer.flip(); 										//Usually called after put!

					while(tcp_buffer.hasRemaining()){

						try{
							tcp_connection[0].write(tcp_buffer);			// Send back final packet
						}
						catch(IOException i){
							System.out.println(i);
						}

					}

					tcp_connection[0].close();								// Close TCP connection
					tcp_connection = new SocketChannel[1];					
				}
				catch(IOException e){
					System.out.println(e);
				}

				timer_started = false;
				global_dest = null;											// Reset global destination
				//msg_to_send.clear();										// Clear messages to send
				//port_to_send.clear();										// Clear ports to send to 
				final_tcp_return = new String[1];
			}

			timer.cancel();													// Cancel timer
		}
	}
	
	// This function handles simultaneous connections
	public void handle_connections(){

		try{
			Selector selector = Selector.open();
			System.setProperty("java.net.preferIPv4Stack" , "true"); 						// Prevents connection to IPv6
			int counter = 0;																// Needed to invoke initialisation
			boolean find_route = false;														// Check if route request was made to this station
			boolean adjacent = false;														// If adjacent station details are found
			boolean wait = true;															// Wait for other stations to initialise
			boolean found_station = false;													// Destination is not adjacent to this station
			ByteBuffer tcp_buffer = ByteBuffer.allocate(1024);								// Buffer to handle TCP connections
			String send_pack;																// Packet to return to browser
			String search_packet;															// Packets sent throughout network


			ServerSocketChannel tcpserver = ServerSocketChannel.open();						// Start TCP connection
			tcpserver.setOption(StandardSocketOptions.SO_REUSEADDR, true);
			tcpserver.configureBlocking(false);
			SocketAddress tcp_endpoint = new InetSocketAddress("127.0.0.1", tcp_port);
			tcpserver.bind(tcp_endpoint);
			tcpserver.register(selector, SelectionKey.OP_ACCEPT);							// Register TCP connection

			udpserver = DatagramChannel.open();												// Start UDP connection
			udpserver.setOption(StandardSocketOptions.SO_REUSEADDR, true);
			udpserver.configureBlocking(false);
			SocketAddress udp_endpoint = new InetSocketAddress("127.0.0.1", udp_port);
			udpserver.bind(udp_endpoint);
			udpserver.register(selector, SelectionKey.OP_READ);								// Register UDP connection


			while(true){
				int count = selector.select();

				if(count==0){
					continue;
				}

				Set<SelectionKey> ready = selector.selectedKeys();
				Iterator iterator = ready.iterator();

				while(iterator.hasNext()){													// Process heard sockets
					SelectionKey key = (SelectionKey) iterator.next();
					iterator.remove(); 														// Remove key so not processed twice
					Channel server_from = (Channel) key.channel(); 							// Get channel key received from

					//TCP connections
					if(key.isValid() && key.isAcceptable() && server_from == tcpserver){ 	// Accept TCP key

						if(tcp_connection[0] == null){
							SocketChannel client = tcpserver.accept(); 						// Accept connection
							client.configureBlocking(false);
							client.register(selector, SelectionKey.OP_READ); 				// Register key so its able to read data
							continue;
						}

					}

					else if(key.isValid() && key.isReadable() && server_from != udpserver){ // Read key for TCP incoming data

						if(tcp_connection[0] == null){
							SocketChannel client = (SocketChannel) key.channel();
						    tcp_connection[0] = client;										// Track this tcp_connection
						    final_tcp_return = new String[1];								// Initialisation of variables
						    fastest_arrival = null;
							timer_started = false;
							global_dest = null;

						    try {
						    	tcp_buffer.clear();
						      	client.read(tcp_buffer); 									// Read data from client
						      	String request = new String(tcp_buffer.array()).trim(); 	// Store read data in a string
						      	Boolean handle = handle_tcp(request);

						      	if(handle==false){											// False is favicon or other request
						      		continue;
						      	}

						      	counter++;													// Increment search holder
						      	find_route = true;											// Search for route accepted
						    }
						  	catch (Exception e) {
						    	System.out.println(e + "in handle key");
						    	continue;
						    }

						}

					}

					//UDP CONNECTIONS
					else if(key.isValid() && key.isReadable() && server_from == udpserver){ 	// UDP READ
						ByteBuffer udp_buffer = ByteBuffer.allocate(1024);						// Buffer to handle UDP connections
						DatagramChannel channel = (DatagramChannel) key.channel();
						udp_buffer.clear();
						udpserver.receive(udp_buffer);

						String data = new String(udp_buffer.array()).trim(); 					// Store read data in a string
						
						// Categorise into correct function
						if(data.contains("REQUEST_STATION")){									
							udp_initialise(data);
							wait = false;
						}

						if(data.contains("Destination")){
							udp_search(data);
						}

						if(data.contains("Found") || data.contains("NO_ROUTE_TODAY")){
							return_packet(data);
						}

					}

				}

				ready.clear(); // Clear key set and wait for new I/O
				
				if(counter>=1 && (adjacent_ports.size() != (inputs_len-3))){ // Invokes initialisation after TCP call

					if((adjacent==false && tt_departure_station.size()==0) || (adjacent_ports.size() != (inputs_len-3))){

						for(int i=3; i<inputs_len; i++){				// Send request to adjacent stations for name
							int port = Integer.parseInt(inputs[i]);

							if(adjacent_ports.contains(port)){
								continue;
							}

							String packet = ("REQUEST_STATION, SOURCE = " + Integer.toString(udp_port) + ", FROM = " + server_name);
							msg_to_send.add(packet);
							port_to_send.add(port);
						}

					}

				}

				if(adjacent_ports.size() == (inputs_len-3)){	// Adjacent stations found
					counter = 4;
					adjacent = true;
				}

				if(counter>3 && find_route==true){	// Begin to handle TCP Request

					if(wait==true){

						try{
						TimeUnit.SECONDS.sleep(10); //Wait for adjacent nodes to initialise
						}
						catch(InterruptedException e){
							System.out.println(e);
						}

						wait = false;
					}

					found_station = false; // Station not found yet
					int num_adj_stations = adjacent_ports.size();

					for(int i=0; i<num_adj_stations; i++){	// Check adjacent stations for destination
						String adj_station = adjacent_st_name.get(i);
						adj_station = adj_station.replaceAll("\\s+",""); // Remove white spaces in string!

						/////////////////////////////////////// CHECK IF STATION IS ADJACENT FIRST! /////////////////////////////////////////////////

						if(adj_station.equals(global_dest)){ // If rows destination == desired destination
							String route_outcome;
							read_timetable();
							String[] r_info = generate_route(tcp_time, global_dest); // Station is adjacent, therefore find next departure

							if(!(r_info[0].equals("NO_ROUTE"))){ // Suitable route found
								route_outcome = ("ROUTE FOUND from " + server_name + " to destination " + global_dest + 
								" leaving on platform " + r_info[3] + " at " + r_info[2] + " on line: " + r_info[1] + 
								", arriving at " + r_info[0]);
							}

							else{ // No direct route found
								route_outcome = "No route today";
							}

							if(!(route_outcome.equals("No route today"))){
								send_pack = ("HTTP/1.1 200 OK\n" +
				                        	"Content-Type: text/html\n" +
				                        	"Connection: close\n" +
				                        	"\n" +
				                        	"<html>\n" +
				                        	"<body>\n" +
				                        	"<h1>" + route_outcome +"</h1>\n" +
				                        	"</body>\n" +
				                        	"</html>\n");
	                        	ByteBuffer this_tcp_send = ByteBuffer.allocate(1024);
	              				this_tcp_send.clear();
								this_tcp_send.put(send_pack.getBytes());
								this_tcp_send.flip(); 									// Usually called after put!

								while(this_tcp_send.hasRemaining()){
									tcp_connection[0].write(this_tcp_send);
								}

								tcp_connection[0].close();
								tcp_connection = new SocketChannel[1];
								found_station = true;
								break;
							}

							else if(timer_started == false){
								timer = new Timer();
								timer.schedule(new shut_tcp(), 5000);					//  Shut TCP connection after 5 seconds
								timer_started = true;
							}
						}

					}

					//////////////////////////////////////////////////// SEND OUT SEARCH PACKET ///////////////////////////////////////////////////////

					if(found_station == false){ // If station still isn't found
						read_timetable();
						num_adj_stations = adjacent_ports.size();

						for(int i=0; i<num_adj_stations; i++){
							String adj_station = adjacent_st_name.get(i);

							if(adj_station.equals(global_dest)){
								continue;
							}

							int adj_port =adjacent_ports.get(i);
							String[] r_info = generate_route(tcp_time, adj_station); // Get info to next station

							if(!(r_info[0].equals("NO_ROUTE"))){ // Send to next node
								search_packet = "Destination = " + global_dest + "; Time_Arrived = " + r_info[0] + "; Visited = " + Integer.toString(udp_port) 
                        		+ "; TTL = 0" + "; Leave_Time = " + r_info[2] + "; Line = " + r_info[1] + "; Platform = " + r_info[3];
							}

							else{
								search_packet = "NO_ROUTE_TODAY"+ "; Visited = " + Integer.toString(udp_port) + "; Arrival = " + r_info[0] + 
                        		"; Origin_time = " + r_info[2] + "; Origin_line = " + r_info[1] + "; Dest =" + global_dest + "; Platform =" + r_info[3];
							}

							msg_to_send.add(search_packet);
							port_to_send.add(adj_port);
						}

					}

					find_route = false; 	//Needed else loop will occur!

				}

				while(!(msg_to_send.isEmpty())){								// Send all found UDP packets around network
					ByteBuffer send_buffer = ByteBuffer.allocate(1024);			// Buffer to hold sending info over UDP
					send_buffer.clear();
					String msg = msg_to_send.remove();
					int port = port_to_send.remove();							
					send_buffer.put(msg.getBytes());
					send_buffer.flip();
					SocketAddress udp_send = new InetSocketAddress("127.0.0.1", port);
					udpserver.send(send_buffer, udp_send);						// Send packets via UDP
				}

			}
		}
		catch(IOException e){
			System.out.println(e +"in find packet");
		}
	}
	
	// Starts and runs server
	public static void main(String[] args){
			station server = new station(args);
			server.handle_connections();
	}
}