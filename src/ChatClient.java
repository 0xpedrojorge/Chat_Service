import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;


public class ChatClient {

    // Variáveis relacionadas com a interface gráfica --- * NÃO MODIFICAR *
    JFrame frame = new JFrame("Chat Client");
    private JTextField chatBox = new JTextField();
    private JTextArea chatArea = new JTextArea();
    // --- Fim das variáveis relacionadas coma interface gráfica

    // Se for necessário adicionar variáveis ao objecto ChatClient, devem
    // ser colocadas aqui
    static private final ByteBuffer outBuffer = ByteBuffer.allocate( 16384 );
    static private final ByteBuffer inBuffer = ByteBuffer.allocate( 16384 );
    static private SocketChannel sc = null;

    // Decoder for incoming text -- assume UTF-8
    static private final Charset charset = Charset.forName("UTF8");
    static private final CharsetDecoder decoder = charset.newDecoder();


    // Método a usar para acrescentar uma string à caixa de texto
    // * NÃO MODIFICAR *
    public void printMessage(final String message) {
        chatArea.append(message);
    }


    // Construtor
    public ChatClient(String server, int port) throws IOException {

        // Inicialização da interface gráfica --- * NÃO MODIFICAR *
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panel.add(chatBox);
        frame.setLayout(new BorderLayout());
        frame.add(panel, BorderLayout.SOUTH);
        frame.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        frame.setSize(500, 300);
        frame.setVisible(true);
        chatArea.setEditable(false);
        chatBox.setEditable(true);
        chatBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    newMessage(chatBox.getText());
                } catch (IOException ex) {
                } finally {
                    chatBox.setText("");
                }
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            public void windowOpened(WindowEvent e) {
                chatBox.requestFocus();
            }
        });
        // --- Fim da inicialização da interface gráfica

        // Se for necessário adicionar código de inicialização ao
        // construtor, deve ser colocado aqui

        Thread receiver = new Thread();
        receiver.run();

        InetSocketAddress isa = new InetSocketAddress(server, port);
        sc = SocketChannel.open(isa);


    }


    // Método invocado sempre que o utilizador insere uma mensagem
    // na caixa de entrada
    public void newMessage(String message) throws IOException {
        outBuffer.clear();
        outBuffer.put(message.getBytes());
        outBuffer.put("\n".getBytes());
        outBuffer.flip();

        while (outBuffer.hasRemaining()) {
            sc.write(outBuffer);
        }
    }


    // Método principal do objecto
    public void run() throws IOException {
        // PREENCHER AQUI
        while (true) {
            inBuffer.clear();
            sc.read( inBuffer );
            inBuffer.flip();

            if (inBuffer.limit()==0) {
                continue;
            }

            String data = decoder.decode(inBuffer).toString();

            String[] dataParts = data.split(" ");
            if (dataParts[0].equals("JOINED") && dataParts.length == 2) {
                printMessage(dataParts[1].strip() + " joined the room.\n");
            } else if (dataParts[0].equals("LEFT") && dataParts.length == 2) {
                printMessage(dataParts[1].strip() + " left the room.\n");
            } else if (dataParts[0].equals("NEWNICK") && dataParts.length == 3) {
                printMessage(dataParts[1] + " changed nickname to " + dataParts[2]);
            } else if (dataParts[0].equals("MESSAGE") && dataParts.length > 2) {
                printMessage(dataParts[1] + ": " + data.substring(dataParts[0].length()+dataParts[1].length()+2));
            } else if (dataParts[0].equals("PRIVATE") && dataParts.length > 2) {
                printMessage("Private message from " + dataParts[1] + ": " + data.substring(dataParts[0].length()+dataParts[1].length()+2));
            } else {
                printMessage(data);
            }

        }

    }


    // Instancia o ChatClient e arranca-o invocando o seu método run()
    // * NÃO MODIFICAR *
    public static void main(String[] args) throws IOException {
        ChatClient client = new ChatClient(args[0], Integer.parseInt(args[1]));
        client.run();
    }
}
