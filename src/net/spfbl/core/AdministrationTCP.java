/*
 * This file is part of SPFBL.
 * 
 * SPFBL is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * SPFBL is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with SPFBL.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.spfbl.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

/**
 * Servidor de commandos em TCP.
 * 
 * Este serviço responde o commando e finaliza a conexão logo em seguida.
 * 
 * @author Leandro Carlos Rodrigues <leandro@spfbl.net>
 */
public final class AdministrationTCP extends Server {

    private final int PORT;
    private final ServerSocket SERVER_SOCKET;
    
    /**
     * Configuração e intanciamento do servidor para comandos.
     * @param port a porta TCP a ser vinculada.
     * @throws java.io.IOException se houver falha durante o bind.
     */
    public AdministrationTCP(int port) throws IOException {
        super("ServerCOMMAND");
        PORT = port;
        setPriority(Thread.MIN_PRIORITY);
        // Criando conexões.
        Server.logDebug("binding command TCP socket on port " + port + "...");
        SERVER_SOCKET = new ServerSocket(port);
    }
    
    /**
     * Inicialização do serviço.
     */
    @Override
    public void run() {
        try {
            Server.logDebug("listening commands on TCP port " + PORT + "...");
            while (continueListenning()) {
                try {
                    String command = null;
                    String result = null;
                    Socket socket = SERVER_SOCKET.accept();
                    long time = System.currentTimeMillis();
                    try {
                        InputStream inputStream = socket.getInputStream();
                        InputStreamReader inputStreamReader = new InputStreamReader(inputStream, "ISO-8859-1");
                        BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                        command = bufferedReader.readLine();
                        if (command == null) {
                            command = "DISCONNECTED";
                        } else {
                            result = AdministrationTCP.this.processCommand(command);
                            // Enviando resposta.
                            OutputStream outputStream = socket.getOutputStream();
                            outputStream.write(result.getBytes("ISO-8859-1"));
                            // Mede o tempo de resposta para estatísticas.
                        }
                    } finally {
                        // Fecha conexão logo após resposta.
                        socket.close();
                        // Log da consulta com o respectivo resultado.
                        Server.logAdministration(time, socket.getInetAddress(), command, result);
                        // Verificar se houve falha no fechamento dos processos.
                        if (result != null && result.equals("ERROR: SHUTDOWN\n")) {
                            // Fechar forçadamente o programa.
                            Server.logDebug("system killed.");
                            System.exit(1);
                        }
                    }
                } catch (SocketException ex) {
                    // Conexão fechada externamente pelo método close().
                    Server.logDebug("command TCP listening stoped.");
                }
            }
        } catch (Exception ex) {
            Server.logError(ex);
        } finally {
            Server.logDebug("command TCP server closed.");
        }
    }
    
    @Override
    protected synchronized void close() throws Exception {
        Server.logDebug("unbinding command TCP socket on port " + PORT + "...");
        SERVER_SOCKET.close();
    }
}