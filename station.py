#!/usr/bin/python3.6
# coding: utf-8

# Author: Kushil Vaghjiani (22503488)
# Version: Final

import sys
import socket
import socketserver
import select
import os
import pandas as pd
import datetime
import time
import queue
import threading

class NetworkServer(object):
    def __init__(self, ports):
        self.tcp_time = ""                                  # Time request received
        data = {"Station":[], "UDP_Port":[]}                # Pandas table columns
        self.adjacent_nodes = pd.DataFrame(data)            # Adjacent stations and ports (PANDAS)
        self.glob_dest = None                               # Destination to reach
        self.outputs = []                                   # Outgoing messages ports
        self.messages = queue.Queue()                       # Messages sent out
        self.search_sent = False                            # Whether adjacent requests are sent
        self.last_timetable_mod = None                      # Last time timetable file modified
        self.tcp_connection = []                            # Handles current TCP connection
        self.tcp_timer = None                               # Timer to shut off TCP connection
        self.timer_started = False                          # Whether timer to shut off is on/off
        self.final_tcp_return = None                        # Final message to return through TCP connection
        self.station_name = str(ports[0])                   # Name of station
        self.tcp_ip_port = int(ports[1])                    # TCP port of server
        self.udp_port = int(ports[2])                       # UDP port of server

        host_ip = "127.0.0.1"                               # Local ip

        # TCP SERVER CONFIG
        self.tcpserver = socketserver.TCPServer((host_ip, self.tcp_ip_port), None, False)       # Initialise tcp connection
        self.tcpserver.allow_reuse_address = True
        self.tcpserver.server_bind()
        self.tcpserver.server_activate()

        # UDP SERVER CONFIG
        self.udpserver = socketserver.UDPServer((host_ip, self.udp_port), None, False)          # Initialise udp connection
        self.udpserver.allow_reuse_address = True
        self.udpserver.server_bind()
        self.udpserver.server_activate()

    # This function generates a data structure to hold and read timetable information
    def generate_timetable(self):
        filename = "tt-" + self.station_name                                # Name of server timetable file
        pre_timetable_mod = self.last_timetable_mod                        

        try:
            self.last_timetable_mod = os.stat(filename).st_mtime            # Ensures timetable isn't read unnecessarily
        except FileNotFoundError:
            print("File doesn't exist")
            return

        if pre_timetable_mod == None or pre_timetable_mod!=self.last_timetable_mod:       
            self.timetable = pd.read_csv(filename, sep=',', encoding='latin1', engine='python', header=None, skiprows = 1)
            self.timetable.columns = ['Departure_Time', 'Line', 'Departure_Station', 'Arrival_Time', 'Destination']

    # This function generates routes to adjacent stations and returns relevent info
    def generate_route(self, request_time, dest):
        destination = dest                                                  # Destination to reach
        departure_time = None
        depart_time = None                                                  #Holds different departure times for calcs
        prev_time_diff = None                                               #For time comparisons for route
        check_time = request_time
        check_time = datetime.datetime.strptime(check_time, "%H:%M")        #Time request made
        arrival = None
        line = None
        platform = None

        for row in range(len(self.timetable)):

            if (self.timetable.loc[row, "Destination"]).strip() != destination.strip(): # If rows destination != desired destination
                continue                                                               

            prev_platform = self.timetable.loc[row,"Departure_Station"]
            depart_time = self.timetable.loc[row, "Departure_Time"]                     #Checks departure time

            if int(depart_time.split(":")[0]) >= 24:                                    #Do not involve times with 24 as the hour
                continue

            depart_time = datetime.datetime.strptime(depart_time, "%H:%M")              #Format departure time
            arrival_time = self.timetable.loc[row, "Arrival_Time"]

            if int(arrival_time.split(":")[0]) >= 24:                                   #Do not involve times with 24 as hour
                continue

            arrival_time = datetime.datetime.strptime(arrival_time, "%H:%M")
            time_diff = depart_time - check_time                                        #Waiting time
            time_diff = time_diff/datetime.timedelta(minutes=1)                         #Give waiting time in minutes
            line_prev = self.timetable.loc[row, "Line"]

            if time_diff < 0:                                                           #Can't have negative time
                continue

            elif departure_time == None:                                                #Initialise departure time
                departure_time = depart_time
                prev_time_diff = time_diff
                arrival = arrival_time                                          
                line = line_prev
                platform = prev_platform

            elif time_diff < prev_time_diff:                                            #We have found a shorter time
                departure_time = depart_time
                prev_time_diff = time_diff
                arrival = arrival_time                                                
                line = line_prev
                platform = prev_platform

            elif time_diff > prev_time_diff:                                            #Since all times are ordered, if greater, then break
                break                                              

        if departure_time == None or arrival == None:                                   #No route today
            return (["NO_ROUTE", "FAILED", "FAILED","FAILED"])

        return([str(arrival.time().strftime("%H:%M")), str(line), str(departure_time.time().strftime("%H:%M")),platform])
    
    # This function handles initial incoming TCP connection
    def tcp_handle(self, request):
        destination = None                                                  # Destination to reach
        destination_request = "="
        incoming_request = None

        if(request[0:3]=="GET"):                                            # Parse lines with GET request
            incoming_request = request.split(" ")[1]

        if ((incoming_request == "/favicon.ico") or (incoming_request == "")):  # Ignore these requests
            request =  ("HTTP/1.1 400 Bad Request\n" +
                        "Connection: close")
            self.tcp_connection[0].sendall(request.encode())
            self.tcp_connection[0].close()
            self.tcp_connection.clear()
            return(False)

        destination_request = incoming_request.split("=")[1]

        if (destination_request != "="):                                    # Get value after equals sign
            destination = destination_request                               # Assign found destination
            self.tcp_time = datetime.datetime.now().strftime("%H:%M")
            #self.tcp_time = "16:00"                                         # Time FOR TESTING! CHANGE!!
            self.glob_dest = destination_request                            # Set destination to reach for server

        return(True)

    # This function initialises server and information
    def udp_initialise(self, data, source):
        outgoing = data.split(",")[0]                                               # Split incoming data into components
        found_ports = self.adjacent_nodes["UDP_Port"].tolist()                      # Adjacent ports

        if outgoing == "REQUEST_STATION":                                           # Send request for station name
            station_source = int(data.split(",")[-2].split("=")[-1])                # Source
            station_from = data.split(",")[-1].split("=")[-1]                       # From
            msg = ("REQUEST_STATION_DETAILS = " + str(self.station_name) + ", SOURCE = " + str(self.udp_port))      # Message to send 
            msg_send = msg.encode("utf-8")
            self.messages.put(msg_send)                                             # Append message to be send within handle_connections
            self.outputs.append(int(source[-1]))                                    # Append port to send corresponding message

            if int(station_source) not in found_ports:                              # If station not in adjacent nodes pd frame
                to_append = {"Station":station_from.strip(),"UDP_Port":int(station_source)}
                self.adjacent_nodes = self.adjacent_nodes.append(to_append, ignore_index = True)    # Append adjacent node to servers info

            if self.search_sent == False:                                           # Send search packet to adjacent nodes

                for i in range(4, len(sys.argv[0:])):

                    if int(sys.argv[i]) in found_ports or int(sys.argv[i]) == station_source :      # Don't send search packets to known addresses
                        continue

                    packet = ("REQUEST_STATION, SOURCE = " + str(self.udp_port)) + ", FROM = " + str(self.station_name) # Send out packet for info
                    encoded_packet = packet.encode('utf-8')
                    self.messages.put(encoded_packet)
                    self.outputs.append(int(sys.argv[i]))
                
            self.search_sent = True   

        elif "REQUEST_STATION_DETAILS" in outgoing:                                 # RECEIVE REQUEST data for station name
            req_station = data.split(",")[0].split("=")[-1]                         # Station received
            station_source = data.split(",")[-1].split("=")[-1]                     # Station received port

            if int(station_source) in found_ports:                                  # If port is already found, ignore.
                return

            to_append = {"Station":req_station,"UDP_Port":int(station_source)}      
            self.adjacent_nodes = self.adjacent_nodes.append(to_append, ignore_index = True)

    # This function sends out search packets for required station
    def udp_search(self, data, source):
        process_data = data.split(";")                                          # Split data into components
        destination = process_data[0].split('=')[-1].strip()                    # Destination to reach
        time_node_arrival = process_data[1].split('=')[-1].strip()              # Time arriving at this node (For searching timetable)
        visited = process_data[2].split('=')[-1]                                # List of visited nodes
        ttl_pack = int(process_data[3].split('=')[-1])                          # TTL counter
        leaving_time = process_data[4].split('=')[-1].strip()                   # Origin leave time
        leaving_line = process_data[5].split('=')[-1].strip()                   # Origin leave line
        leaving_platform = process_data[6].split('=')[-1].strip()               # Origin leave platform

        if (str(self.udp_port) in visited) or ttl_pack == 25:                   #Packet has already visited this station or done too many hops
            return                                                              #Packet terminated

        self.generate_timetable()

        ########################################################## Node is adjacent ##################################################################
        for row in range(len(self.adjacent_nodes.index)):                                   # Check adjacent stations first

            if (self.adjacent_nodes.loc[row, "Station"].strip()) == destination.strip():    # If we have adjacent destination
                r_info = self.generate_route(time_node_arrival, destination)                # Return route info to destination

                if r_info[0] != "NO_ROUTE":                                                 # Route found
                    found_packet = "Found "+ "; Visited = " + visited + "; Arrival = " + r_info[0] + "; Origin_time = " + leaving_time + \
                    "; Origin_line = " + leaving_line + "; Dest =" + destination + "; Platform =" + leaving_platform

                elif r_info[0] == "NO_ROUTE":                                               # No route found
                    found_packet = "NO_ROUTE_TODAY" + "; Visited = " + visited + "; Arrival = " + r_info[0] + "; Origin_time = " + \
                    leaving_time + "; Origin_line = " + leaving_line + "; Dest =" + destination + "; Platform =" + leaving_platform

                encoded_found_packet = found_packet.encode('utf-8')
                self.messages.put(encoded_found_packet)
                self.outputs.append(int(visited.split(",")[-1]))
                
                if r_info[0] != "NO_ROUTE":                                                 # If no route, continue to send around
                    return                                                                  # Break search if route is found

        visited += "," + str(self.udp_port)                                                 # Add current station to visited string

        ########################################################## Node isn't adjacent ###############################################################

        ttl_pack+=1                                                                         # Increase TTL tracker

        for i in range(0, len(self.adjacent_nodes.index)):                                  #Packet not found, therefore send to other nodes.
            adj_node = int(self.adjacent_nodes.loc[i, "UDP_Port"])

            if adj_node == int(source[-1]) or str(adj_node) in visited or (self.adjacent_nodes.loc[i, "Station"].strip()) == destination.strip():   
                #Don't send back to node, message came from, or already visited node or destination node (NO ROUTE CASE)!.
                continue    #Prevents redundant packets being sent

            r_info = self.generate_route(time_node_arrival, str(self.adjacent_nodes.loc[i, "Station"]).strip()) # Info to next node
            search_packet = "Destination = " + destination + "; Time_Arrived = " + r_info[0] + "; Visited = " + visited + "; TTL = " + \
                        str(ttl_pack) + "; Leave_Time = " + leaving_time + "; Line = " + leaving_line + "; Platform = " + leaving_platform

            if r_info[0] == "NO_ROUTE":     # If no route to adjacent node
                search_packet = "NO_ROUTE_TODAY" + "; Visited = " + visited + "; Arrival = " + r_info[0] + "; Origin_time = " + leaving_time + \
                        "; Origin_line = " + leaving_line + "; Dest =" + destination + "; Platform =" + leaving_platform
            
            encoded_search_packet = search_packet.encode('utf-8')
            self.messages.put(encoded_search_packet)
            self.outputs.append(int(self.adjacent_nodes.loc[i,"UDP_Port"]))

    # This function handles returning packet to original node
    def return_packet(self, data, source):
        process_data = data.split(";")
        status = process_data[0].strip()                                # Route found or not?
        route = process_data[1].split("=")[-1].strip().split(",")       # Route remaining for return
        arrive_time = process_data[2].split("=")[-1]                    # Arrival time info
        leave_time = process_data[3].split("=")[-1]                     # Original leave time
        leave_line = process_data[4].split("=")[-1]                     # Original leave line
        destination = process_data[5].split("=")[-1].strip()            # Destination searched from origin
        leaving_platform = process_data[6].split("=")[-1].strip()       # Original leaving platform
        route_outcome = None

        if route[0] == '':                                              # At origin node

            if destination != self.glob_dest:                           # If destinations don't match, terminate
                return

            outcome = ("ROUTE FOUND from " + self.station_name + " to destination " + self.glob_dest + " leaving on platform " +  \
                leaving_platform + " at " + leave_time.strip() + " on line: " + leave_line.strip() + ", arriving at " + arrive_time.strip())
            
            if status != "NO_ROUTE_TODAY" and arrive_time!="NO_ROUTE":  #If route exists
                
                if self.fastest_arrival=="NO_ROUTE_TODAY" or self.fastest_arrival==None:    # We have found a route
                    self.fastest_arrival = arrive_time          # Updating values
                    route_outcome = outcome
                    self.final_tcp_return = route_outcome
                
                if self.fastest_arrival!="NO_ROUTE_TODAY":  
                    old_arrival = datetime.datetime.strptime(self.fastest_arrival.strip(),"%H:%M")
                    new_arrive_time = datetime.datetime.strptime(arrive_time.strip(),"%H:%M")

                    if new_arrive_time < old_arrival:           # Found a faster route
                        self.fastest_arrival=arrive_time
                        route_outcome = outcome
                        self.final_tcp_return=route_outcome     # Current fastest route

            else:   # No route found

                if self.fastest_arrival == None:                # If fastest arrival hasn't initialised
                    self.fastest_arrival = "NO_ROUTE_TODAY"
                    route_outcome = "No route today."
                    self.final_tcp_return = route_outcome

            if route_outcome!=None:

                if len(self.tcp_connection)==0:     # Connection is closed
                    return

                if self.timer_started == False:     # Check if TCP timer is on or not
                    self.tcp_timer = threading.Timer(5.0, self.shut_tcp)    # Shut TCP after 5 seconds of being open
                    self.tcp_timer.start()
                    self.timer_started = True

            return #Route found and stored

        next_dest = int(route[-1])
        del route[-1]
        route = ','.join(route)

        if status == "NO_ROUTE_TODAY":
            found_packet = "NO_ROUTE_TODAY"+ "; Visited = " + route + "; Arrival = " + arrive_time + "; Origin_time = " + leave_time + \
            "; Origin_line = " + leave_line + "; Dest =" + destination + "; Platform =" + leaving_platform
        else:
            found_packet = "Found "+ "; Visited = " + route + "; Arrival = " + arrive_time + "; Origin_time = " + leave_time + \
            "; Origin_line = " + leave_line + "; Dest =" + destination + "; Platform =" + leaving_platform

        encoded_found_packet = found_packet.encode('utf-8')
        self.messages.put(encoded_found_packet)
        self.outputs.append(next_dest)   

    # This function shuts TCP connection
    def shut_tcp(self):

        if len(self.tcp_connection)!=0:                         # If connection still exists

            if self.final_tcp_return == None:                   # If nothing returned from searches
                self.final_tcp_return = "No route found"

            send_pack = ("HTTP/1.1 200 OK\n" +                  # HTML sent back to browser
                        "Content-Type: text/html\n" +
                        "Connection: keep-alive\n" +
                        "\n"
                        "<html>\n" +
                        "<body>\n" +
                        "<h1>" + self.final_tcp_return +"</h1>\n" +
                        "</body>\n" +
                        "</html>\n")

            self.tcp_connection[0].sendall(send_pack.encode())  # Send back final packet
            self.tcp_connection[0].close()                      # Close TCP connection
            self.tcp_connection.clear()
            self.timer_started = False                          # Reset variables for new connection
            self.glob_dest = None
            self.final_tcp_return = None
            #self.outputs.clear()                                # Don't send searches for old destination 
            #with self.messages.mutex:
            #    self.messages.queue.clear()

    #This function handles simultaneous connections
    def handle_connections(self):
        listen = [self.tcpserver.socket, self.udpserver.socket]     # Listen to these sockets
        write = []                                                  # Listen if writing sockets are ready
        adjacent = False                                            # If adjacent station details are found
        count = 0                                                   # Needed to invoke initialisation
        find_route = False                                          # Check if route request was made to this station
        wait = True                                                 # Wait for other stations to initialise
        found_station = False                                       # Destination is not adjacent to this station

        while True:
            connections, writable, elist = select.select(listen, write, [], 1)  

            if [connections, writable, elist] == [[], [], []]:
                continue  

            else:
                for socket in connections:                          # Process heard sockets

                    if socket == listen[0]:                         # Listen for TCP connections

                        if len(self.tcp_connection) == 0:
                            self.fastest_arrival = None             # Initialise variables
                            self.tcp_timer = None
                            self.tcp_connection.clear()
                            self.timer_started = False
                            self.glob_dest = None
                            self.final_tcp_return = None
                            #self.outputs.clear()                    # Stop previous searches
                            #with self.messages.mutex:
                            #    self.messages.queue.clear()

                            conn,addr = socket.accept()             # Accept TCP connection
                            self.tcp_connection.append(conn)
                            request = str(conn.recv(1024).decode())
                           
                            if request == "" or request == None:
                                del connections[0]
                                continue

                            outcome = self.tcp_handle(request)      # Handle TCP request

                            if outcome == False:                    # False is favicon or other request
                                continue
                            
                            count+=1                                # Increment search holder
                            find_route = True                       # Search for route accepted

                    elif socket == listen[1]:                       # Listen for UDP calls
                        data_rec,source = socket.recvfrom(1024)     # Receive data
                        data = data_rec.decode('utf-8')

                        if data != "":                              # Categorise into correct function

                            if "REQUEST_STATION" in data or "REQUEST_STATION_DETAILS" in data:
                                self.udp_initialise(data, source)
                                wait = False                   

                            if "Destination" in data:
                                self.udp_search(data, source)

                            if "Found" in data or "NO_ROUTE_TODAY" in data:
                                self.return_packet(data, source)

                            write.append(socket)

                for send in writable:          # Process write/send requests

                    for i in self.outputs:                          # Send out messages in order
                        msg_to_send = self.messages.get()           # Send out self.messages in order formed
                        port_to_send = int(i)                       # To corresponding port
                        self.udpserver.socket.sendto(msg_to_send, ("127.0.0.1", port_to_send))
                        del self.outputs[0]                         # Delete messages as sent

                    del write[0]

            if count >= 1 and len(self.adjacent_nodes.index) != (len(sys.argv[0:])) - 4:  # Initialise after TCP calls

                if((adjacent == False and len(self.adjacent_nodes.index)==0) or (len(self.adjacent_nodes.index) != (len(sys.argv[0:])) - 4)):

                    for i in range(4, len(sys.argv[0:])):   # Send request to adjacent stations for name

                        if sys.argv[i] in self.adjacent_nodes["UDP_Port"].tolist():
                            continue

                        packet = "REQUEST_STATION, SOURCE = " + str(self.udp_port) + ", FROM = " + str(self.station_name)
                        encoded_packet = packet.encode('utf-8')
                        self.messages.put(encoded_packet)
                        self.outputs.append(sys.argv[i])

            if (len(self.adjacent_nodes.index) == (len(sys.argv[0:])) - 4): # Make sure dataframe is filled to amount of stations
                count = 4
                adjacent = True
            
            if count > 3 and find_route == True: # Begin to handle TCP Request

                ############################################# CHECK IF STATION IS ADJACENT FIRST! #############################################
                if wait==True:
                    time.sleep(10)  # Make sure adjacent stations have initialised maybe increase?
                    wait = False

                found_station = False   # Station not found yet

                for row in range(len(self.adjacent_nodes.index)): #Check adjacent stations first

                    if (self.adjacent_nodes.loc[row, "Station"].strip()) == self.glob_dest.strip():  # If rows destination == desired destination
                        self.generate_timetable()
                        r_info = self.generate_route(self.tcp_time, self.glob_dest)        # Station is adjacent, therefore find next departure

                        if r_info[0] != "NO_ROUTE": # Suitable route found
                            route_outcome = ("ROUTE FOUND from " + self.station_name + " to destination " + self.glob_dest + " leaving on platform " + r_info[3] + \
                                " at " + r_info[2] + " on line: " + r_info[1] + ", arriving at " + r_info[0])
            
                        elif r_info[0] == "NO_ROUTE": # No direct route found
                            route_outcome = "No route today"

                        if route_outcome != "No route today":
                            send_pack = ("HTTP/1.1 200 OK\n" +
                            "Content-Type: text/html\n" +
                            "Connection: close\n" +
                            "\n"
                            "<html>\n" +
                            "<body>\n" +
                            "<h1>" + route_outcome +"</h1>\n" +
                            "</body>\n" +
                            "</html>\n")

                            self.tcp_connection[0].sendall(send_pack.encode())
                            self.tcp_connection[0].close()
                            self.tcp_connection.clear()
                            found_station = True
                            break 
                        
                        else:
                            if self.timer_started == False:     # Check if TCP timer is on or not
                                self.tcp_timer = threading.Timer(5.0, self.shut_tcp)    # Shut TCP after 5 seconds of being open
                                self.tcp_timer.start()          # Start timer if no route found adjacently
                                self.timer_started = True

                ############################################# SEND OUT SEARCH PACKET #########################################################

                if found_station == False:  # If station still isn't found
                    self.generate_timetable()

                    for i in range(0, len(self.adjacent_nodes.index)):

                        if (self.adjacent_nodes.loc[i, "Station"]).strip() == self.glob_dest:    # Don't send to global station!
                            continue

                        r_info = self.generate_route(self.tcp_time, str(self.adjacent_nodes.loc[i, "Station"]).strip()) # Get info to next station

                        if r_info[0]!="NO_ROUTE": # Send to next node
                            search_packet = "Destination = " + self.glob_dest + "; Time_Arrived = " + r_info[0] + "; Visited = " + str(self.udp_port) \
                            + "; TTL = 0" + "; Leave_Time = " + r_info[2] + "; Line = " + r_info[1] + "; Platform = " + r_info[3]
                        
                        else:
                            search_packet = "NO_ROUTE_TODAY"+ "; Visited = " + str(self.udp_port) + "; Arrival = " + r_info[0] + \
                            "; Origin_time = " + r_info[2] + "; Origin_line = " + r_info[1] + "; Dest =" + self.glob_dest + "; Platform =" + r_info[3]
                        
                        encoded_search_packet = search_packet.encode('utf-8')
                        self.messages.put(encoded_search_packet)
                        self.outputs.append(int(self.adjacent_nodes.loc[i,"UDP_Port"]))

                find_route = False #REQUIRED ELSE CODE WILL LOOP!
            
            if len(self.outputs)!=0:    # Check if we can send packets
                write.append(self.udpserver.socket)


# Starts and runs server
if __name__ == '__main__':
    netserver = NetworkServer(sys.argv[1:]) #Holds TCP and UDP connections
    netserver.handle_connections()