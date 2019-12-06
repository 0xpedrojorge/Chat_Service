import javax.swing.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.Pattern;


public class ChatServer {
    // A pre-allocated buffer for the received data
    static private final ByteBuffer buffer = ByteBuffer.allocate( 16384 );

    // Decoder for incoming text -- assume UTF-8
    static private final Charset charset = Charset.forName("UTF8");
    static private final CharsetDecoder decoder = charset.newDecoder();

    // HashMaps for the users and the rooms
    static private HashMap<SocketChannel, User> users = new HashMap<>(); // Map each socket to its selector key


    static public void main( String args[] ) throws Exception {
        // Parse port from command line
        int port = Integer.parseInt( args[0] );

        try {
            // Instead of creating a ServerSocket, create a ServerSocketChannel
            ServerSocketChannel ssc = ServerSocketChannel.open();

            // Set it to non-blocking, so we can use select
            ssc.configureBlocking( false );

            // Get the Socket connected to this channel, and bind it to the
            // listening port
            ServerSocket ss = ssc.socket();
            InetSocketAddress isa = new InetSocketAddress( port );
            ss.bind( isa );

            // Create a new Selector for selecting
            Selector selector = Selector.open();

            // Register the ServerSocketChannel, so we can listen for incoming
            // connectionsSys
            ssc.register( selector, SelectionKey.OP_ACCEPT );
            System.out.println( "Listening on port "+port );

            // D) Creating a list to store the connections
            LinkedList<SocketChannel> sockets = new LinkedList<>();

            while (true) {
                // See if we've had any activity -- either an incoming connection,
                // or incoming data on an existing connection
                int num = selector.select();

                // If we don't have any activity, loop around and wait again
                if (num == 0) {
                    continue;
                }

                // Get the keys corresponding to the activity that has been
                // detected, and process them one by one
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                while (it.hasNext()) {
                    // Get a key representing one of bits of I/O activity
                    SelectionKey key = it.next();

                    // What kind of activity is it?
                    if (key.isAcceptable()) {

                        // It's an incoming connection.  Register this socket with
                        // the Selector so we can listen for input on it
                        Socket s = ss.accept();
                        System.out.println( "Got connection from "+ s );

                        // Make sure to make it non-blocking, so we can use a selector
                        // on it.
                        SocketChannel sc = s.getChannel();
                        sc.configureBlocking( false );

                        // Associate a user to the SocketChannel
                        User newUser = new User("new_user_" + users.size());
                        users.put(sc, newUser);

                        // Register it with the selector, for reading
                        sc.register( selector, SelectionKey.OP_READ, newUser );

                    } else if (key.isReadable()) {

                        SocketChannel sc = null;

                        try {

                            // It's incoming data on a connection -- process it
                            sc = (SocketChannel)key.channel();

                            // Get data from the socket to the buffer
                            buffer.clear();
                            sc.read( buffer );
                            buffer.flip();

                            // If no data, close the connection
                            if (buffer.limit()==0) {
                                continue;
                            }

                            // Store the data as a string
                            String messageReceived = decoder.decode(buffer).toString();
                            users.get(sc).setBuffer(users.get(sc).getBuffer()+messageReceived);
                            if (messageReceived.charAt(messageReceived.length()-1) != '\n') {
                                continue;
                            }
                            String line = users.get(sc).getBuffer();
                            users.get(sc).setBuffer("");

                            String[] messages = line.split("\n");
                            for (int i=0; i<messages.length; i++) {
                                boolean ok = processInput( sc, (User) key.attachment(), messages[i] );

                                // If the connection is dead, remove it from the selector
                                // and close it
                                if (!ok) {
                                    key.cancel();

                                    Socket s = null;
                                    try {
                                        s = sc.socket();
                                        User left = users.get(sc);
                                        if (left.getState().equals("inside"))
                                            messageRoom(left, left.getRoom(), "LEFT "+left.getNick()+"\n");
                                        users.remove(sc);
                                        System.out.println( "Closing connection to "+s );
                                        s.close();
                                    } catch( IOException ie ) {
                                        System.err.println( "Error closing socket "+s+": "+ie );
                                    }
                                }
                            }

                        } catch( IOException ie ) {

                            // On exception, remove this channel from the selector
                            key.cancel();

                            try {
                                sc.close();
                            } catch( IOException ie2 ) { System.out.println( ie2 ); }

                            System.out.println( "Closed "+sc );
                        }
                    }
                }

                // We remove the selected keys, because we've dealt with them.
                keys.clear();
            }
        } catch( IOException ie ) {
            System.err.println( ie );
        }
    }

    // Send a message back to a user
    static private boolean messageBack( SocketChannel sc, String message) throws IOException {
        buffer.clear();
        buffer.put(message.getBytes());
        buffer.flip();

        // If no data, close the connection
        if (buffer.limit()==0) {
            return false;
        }

        //Send the message to the user
        while (buffer.hasRemaining()) {
            sc.write(buffer);
        }

        return true;
    }

    // Read the message to the buffer and return messageRoom(user, user.getRoom(), message.substring(1)); distribute to everybody in the same room
    static private boolean messageRoom (User me, String room, String message) throws IOException {
        buffer.clear();
        buffer.put(message.getBytes());
        buffer.flip();

        // If no data, close the connection
        if (buffer.limit()==0) {
            return false;
        }

        for ( SocketChannel s : users.keySet() ) {
            if(users.get(s).getRoom().equals(room)) {
                while (buffer.hasRemaining()) {
                    s.write(buffer);
                }
            }
            buffer.rewind();
        }
        return true;
    }

    // Process a message and redirect to other methods
    static private boolean processInput( SocketChannel sc, User user, String line ) throws IOException {

        // Decode and get the message
        String message = line;
        String start;
        if (message.contains(" "))
            start = message.split(" ")[0];
        else
            start = message.strip();


        if (start.charAt(0) =='/') {
            if (start.length()==1) {
                message = message.substring(1);

            } else {
                if (start.charAt(1) != '/') {
                    switch (start) {
                        case "/priv":
                            if (message.split(" ").length < 3) {
                                messageBack(sc, "ERROR\n");
                                return true;
                            }
                            return processCommand(sc, user, start, message.split(" ")[1].strip(), message.substring(message.split(" ")[0].length()+message.split(" ")[1].length()+2));
                        case "/nick": case "/join":
                            if (message.split(" ").length != 2) {
                                messageBack(sc, "ERROR\n");
                                return true;
                            }
                            return  processCommand(sc, user, start, message.split(" ")[1].strip(), null);
                        case "/leave": case "/bye":
                            if (message.split(" ").length != 1) {
                                messageBack(sc, "ERROR\n");
                                return true;
                            }
                            return processCommand(sc, user, start, null, null);
                        default:
                            message = message.substring(1);
                            break;

                    }
                } else {
                    message = message.substring(1);
                }
            }
        }
        if (user.getState().equals("inside")) {
            return messageRoom(user, user.getRoom(), "MESSAGE "+ user.getNick() + " " + message+"\n");
        }
        return messageBack(sc, "ERROR\n");
    }

    // Process the message in case it is a command
    static private boolean processCommand( SocketChannel sc, User user, String command, String arg, String message) throws IOException {
        //Check what the command is and execute
        switch ( command ) {
            case "/priv":
                SocketChannel receiver = null;
                for ( SocketChannel s : users.keySet() ) {
                    if(users.get(s).getNick().equals(arg)) {
                        // In case the nick is found
                        receiver = s;
                    }
                }
                if ( receiver == null ) {
                    messageBack(sc, "ERROR\n");
                    break;
                }
                messageBack(sc, "OK\n");
                messageBack(receiver, "PRIVATE " + user.getNick() + " " + message + "\n");
                break;
            case "/nick":
                // Check if name is available
                boolean nickAvailable = true;
                for ( SocketChannel s : users.keySet() ) {
                    if(users.get(s).getNick().equals(arg)) {
                        // In case the nick is used signal as not available
                        nickAvailable = false;
                    }
                }

                if(!nickAvailable) {
                    // In case the nick is used message back error
                    messageBack(sc, "ERROR\n");
                    break;
                } else {
                    // Get the user and change the nick and state
                    String oldNick = user.getNick();
                    user.setNick(arg);
                    if (user.getState().equals("init")) {
                        user.setState("outside");
                    } else if (user.getState().equals("inside")) {
                        messageRoom(user, user.getRoom(), "NEWNICK " + oldNick + " " + arg + "\n");
                    }

                    // Warn the user the change was made
                    messageBack(sc, "OK\n");
                }
                break;

            case "/join":
                String oldRoom = "";
                if (user.getState().equals("init")) {
                    messageBack(sc, "ERROR\n");
                    break;
                } else if (user.getState().equals("inside")) {
                    oldRoom = user.getRoom();
                }
                user.setState("inside");
                user.setRoom(arg);
                messageBack(sc, "OK\n");
                messageRoom(user, arg, "JOINED " + user.getNick()+"\n");
                if (!oldRoom.equals(""))
                    messageRoom(user, oldRoom, "LEFT "+user.getNick()+"\n");
                break;
            case "/leave":
                if (user.getState().equals("init") || user.getState().equals("outside")) {
                    messageBack(sc, "ERROR\n");
                    break;
                }
                String room = user.getRoom();
                messageBack(sc, "OK\n");
                user.setRoom("");
                user.setState("outside");
                messageRoom(user, room, "LEFT " + user.getNick() + "\n");
                break;
            case "/bye":
                String r = "";
                if (user.getState().equals("inside")) {
                    r = user.getRoom();
                    user.setRoom("");
                    user.setState("outside");
                    messageRoom(user, r, "LEFT "+user.getNick() + "\n");
                }
                messageBack(sc, "BYE\n");
                return false;
        }
        return true;
    }

}

