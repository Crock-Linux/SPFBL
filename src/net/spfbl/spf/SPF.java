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
 * along with SPFBL. If not, see <http://www.gnu.org/licenses/>.
 */
package net.spfbl.spf;

import net.spfbl.core.Core;
import net.spfbl.core.NormalDistribution;
import net.spfbl.whois.Domain;
import net.spfbl.core.ProcessException;
import net.spfbl.core.Server;
import net.spfbl.whois.Owner;
import net.spfbl.whois.Subnet;
import net.spfbl.whois.SubnetIPv4;
import net.spfbl.whois.SubnetIPv6;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.Serializable;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.naming.CommunicationException;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.ServiceUnavailableException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InvalidAttributeIdentifierException;
import net.spfbl.core.Analise;
import net.spfbl.core.Client;
import net.spfbl.data.Block;
import net.spfbl.core.Defer;
import net.spfbl.data.Ignore;
import net.spfbl.data.NoReply;
import net.spfbl.core.Peer;
import net.spfbl.data.Provider;
import net.spfbl.core.Reverse;
import net.spfbl.core.User;
import net.spfbl.data.Trap;
import net.spfbl.data.White;
import org.apache.commons.lang3.SerializationUtils;

/**
 * Representa o registro SPF de um deterninado hostname.
 *
 * Implementação da RFC 7208, com algumas modificações para atender condições
 * específicas.
 *
 * Quando a consulta é feita, o resultado do SPF é considerado para determinar o
 * responsável pela mensagem. Uma vez encontrado o responsável, um ticket SPFBL
 * é gerado através de criptografia na base 64. Este ticket é enviado juntamente
 * o qualificador SPF da consulta. O cliente da consulta deve extrair o ticket
 * do resultado e adicionar no cabeçalho da mensagem utilizando o campo
 * "Received-SPFBL".
 *
 * A regra de determinação de responsabilidade é usada para gerar o ticket SPFBL
 * e funciona da seguinte forma: 1. Se retornar PASS, o remetente é o
 * responsável pela mensagem ou 2. Caso contrário, o hostname é responsável pela
 * mensagem.
 *
 * No primeiro caso, onde o remetente é responsável pela mensagem, o ticket é
 * gerado com a seguinte regra: 1. Se o domínio do rementente estiver na lista
 * de provedores, então o endereço de e-mail completo é utilizado ou 2. Caso
 * contrário, o hostname e domínio do rementente são utilizados.
 *
 * No segundo caso, onde o hostname é responsável pela mensagem, o ticket é
 * gerado com a seguinte regra: 1. Se o HELO apontar para o IP, então o próprio
 * HELO e o domínio do HELO são utilizados ou 2. Caso contrário, o IP é
 * utilizado.
 *
 * Todas as consultas são registradas numa distribuição de probabilidade, onde é
 * possível alternar de HAM para SPAM utilizando o ticket gerado. Uma vez
 * recebida a reclamação com o ticket, o serviço descriptografa o ticket e
 * extrai os responsaveis pelo envio.
 *
 * @author Leandro Carlos Rodrigues <leandro@spfbl.net>
 */
public final class SPF implements Serializable {

    private static final long serialVersionUID = 1L;
    private final String hostname;
    private String redirect = null;
    private String explanation = null;
    private ArrayList<Mechanism> mechanismList = null;
    private Qualifier all = null; // Qualificador do mecanismo all.
    private boolean error = false; // Se houve erro de sintaxe.
    private int queries = 0; // Contador de consultas.
    private int nxdomain = 0; // Contador de inexistência de domínio.
    private long lastRefresh = 0; // Última vez que houve atualização do registro em milisegundos.
    private static final int REFRESH_TIME = 7; // Prazo máximo que o registro deve permanecer em cache em dias.
    
    private SPF(String hostname) throws ProcessException {
        this.hostname = hostname;
        // Sempre usar best-guess em caso de 
        // indisponibilidade de DNS na primeira consulta.
        refresh(false, true);
    }

    /**
     * Consulta o registro SPF nos registros DNS do domínio. Se houver mais de
     * dois registros diferentes, realiza o merge do forma a retornar um único
     * registro.
     *
     * @param hostname o nome do hostname para consulta do SPF.
     * @param bgWhenUnavailable usar best-guess quando houver erro temporário
     * para alcançar o registro.
     * @return o registro SPF consertado, padronuzado e mergeado.
     * @throws ProcessException
     */
    private static LinkedList<String> getRegistrySPF(String hostname,
            boolean bgWhenUnavailable) throws ProcessException {
        LinkedList<String> registryList = new LinkedList<String>();
        try {
//            if (CacheGuess.contains(hostname)) {
//                // Sempre que houver registro de
//                // chute, sobrepor registro atual.
//                registryList.add(CacheGuess.get(hostname));
//            } else {
//                // Caso contrário procurar nos
//                // registros oficiais do domínio.
                try {
                    Attributes attributes = Server.getAttributesDNS(
                            hostname, new String[]{"SPF"});
                    Attribute attribute = attributes.get("SPF");
                    if (attribute != null) {
                        for (int index = 0; index < attribute.size(); index++) {
                            String registry = (String) attribute.get(index);
                            if (registry.contains("v=spf1 ")) {
                                registry = fixRegistry(registry);
                                if (!registryList.contains(registry)) {
                                    registryList.add(registry);
                                }
                            }
                        }
                    }
                } catch (InvalidAttributeIdentifierException ex) {
                    // Não encontrou registro SPF.
                }
                if (registryList.isEmpty()) {
                    try {
                        Attributes attributes = Server.getAttributesDNS(
                                hostname, new String[]{"TXT"});
                        Attribute attribute = attributes.get("TXT");
                        if (attribute != null) {
                            for (int index = 0; index < attribute.size(); index++) {
                                String registry = (String) attribute.get(index);
                                if (registry.contains("v=spf1 ")) {
                                    registry = fixRegistry(registry);
                                    if (!registryList.contains(registry)) {
                                        registryList.add(registry);
                                    }
                                }
                            }

                        }
                    } catch (InvalidAttributeIdentifierException ex2) {
                        // Não encontrou registro TXT.
                    }
                }
//            }
            if (registryList.isEmpty()) {
//                hostname = "." + hostname;
//                if (CacheGuess.contains(hostname)) {
//                    // Significa que um palpite SPF
//                    // foi registrado para este hostname.
//                    // Neste caso utilizar o paltpite específico.
//                    registryList.add(CacheGuess.get(hostname));
//                } else {
//                    // Se não hoouver palpite específico para o hostname,
//                    // utilizar o palpite padrão, porém adaptado para IPv6.
//                    // http://www.openspf.org/FAQ/Best_guess_record
//                    registryList.add(CacheGuess.BEST_GUESS);
//                }
                
                // Como o domínio não tem registro SPF,
                // utilizar um registro SPF de chute do sistema.
                String guess = CacheGuess.get(hostname);
                registryList.add(guess);
            }
            return registryList;
        } catch (NameNotFoundException ex) {
            return null;
        } catch (NamingException ex) {
            if (bgWhenUnavailable) {
                // Na indisponibilidade do DNS
                // utilizar um registro SPF de chute do sistema.
                String guess = CacheGuess.get(hostname);
                registryList.add(guess);
                return registryList;
            } else if (ex instanceof CommunicationException) {
                throw new ProcessException("ERROR: DNS UNAVAILABLE");
            } else {
                throw new ProcessException("ERROR: DNS UNAVAILABLE", ex);
            }
        } catch (Exception ex) {
            throw new ProcessException("ERROR: FATAL", ex);
        }
    }

    /**
     * Algoritmo para consertar e padronizar o registro SPF.
     *
     * @param registry o registro SPF original.
     * @return o registro SPF consertado e padronizado.
     */
    private static String fixRegistry(String registry) {
        String vesion = "v=spf1";
        String all = null;
        String redirect = null;
        String explanation = null;
        LinkedList<String> midleList = new LinkedList<String>();
        LinkedList<String> errorList = new LinkedList<String>();
        registry = registry.replace("\\\"", "\"");
        registry = registry.replace("\" \"", "");
        registry = registry.replace("\"", "");
        registry = registry.toLowerCase();
        StringTokenizer tokenizer = new StringTokenizer(registry, " ");
        while (tokenizer.hasMoreTokens()) {
            Boolean valid;
            String token = tokenizer.nextToken();
            if (token.equals("v=spf1")) {
                vesion = token;
                valid = null;
            } else if (token.startsWith("redirect=")) {
                redirect = token;
                valid = null;
            } else if (token.startsWith("exp=")) {
                explanation = token;
                valid = null;
            } else if (token.equals("v=msv1")) {
                valid = true;
            } else if (token.startsWith("t=") && token.length() == 32) {
                valid = true;
            } else if (isMechanismMiddle(token)) {
                valid = true;
            } else if (isMechanismAll(token)) {
                all = token;
                valid = null;
            } else {
                valid = false;
            }
            if (valid == null) {
                mergeMechanism(midleList, errorList);
            } else if (valid == true) {
                mergeMechanism(midleList, errorList);
                if (!midleList.contains(token)) { // Não considera tokens repetidos.
                    midleList.add(token);
                }
            } else if (valid == false) {
                errorList.add(token);
            }
        }
        registry = vesion;
        if (redirect == null) {
            for (String token : midleList) {
                registry += ' ' + token;
            }
            if (all != null) {
                registry += ' ' + all;
            }
        } else {
            registry += ' ' + redirect;
        }
        if (explanation != null) {
            registry += ' ' + explanation;
        }
        return registry;
    }

    /**
     * Merge nas listas de fixação de SPF.
     *
     * @param midleList lista dos mecanismos centrais.
     * @param errorList lista dos mecanismos com erro de sintaxe.
     */
    private static void mergeMechanism(
            LinkedList<String> midleList,
            LinkedList<String> errorList) {
        while (!errorList.isEmpty()) {
            boolean fixed = false;
            if (errorList.size() > 1) {
                for (int index = 1; index < errorList.size(); index++) {
                    String tokenFix = errorList.getFirst();
                    for (String tokenError : errorList.subList(1, index + 1)) {
                        tokenFix += tokenError;
                    }
                    if (isMechanismMiddle(tokenFix)) {
                        midleList.add(tokenFix);
                        int k = 0;
                        while (k++ <= index) {
                            errorList.removeFirst();
                        }
                        fixed = true;
                        break;
                    }
                }

            }
            if (!fixed) {
                // Não foi capaz de corrigir o erro.
                midleList.add(errorList.removeFirst());
            }
        }
    }

    /**
     * Verifica se o whois é um mecanismo cental.
     *
     * @param token o whois do registro SPF.
     * @return verdadeiro se o whois é um mecanismo cental.
     */
    private static boolean isMechanismMiddle(String token) {
        if (isMechanismIPv4(token)) {
            return true;
        } else if (isMechanismIPv6(token)) {
            return true;
        } else if (isMechanismA(token)) {
            return true;
        } else if (isMechanismMX(token)) {
            return true;
        } else if (isMechanismPTR(token)) {
            return true;
        } else if (isMechanismExistis(token)) {
            return true;
        } else if (isMechanismInclude(token)) {
            return true;
        } else {
            return false;
        }
    }
    
    private boolean isInexistent() {
        return nxdomain > 0;
    }

    private boolean isDefinitelyInexistent() {
        // Se consultou mais de 32 vezes 
        // seguidas com 3 retornos de inexistência,
        // considerar como definitivamente inexistente.
        return nxdomain > 3 && queries > 32;
    }

    /**
     * Método seguro para incrementar nxdomain sem deixar que ele se torne
     * negativo.
     */
    private void addInexistent() {
        if (nxdomain < Integer.MAX_VALUE) {
            nxdomain++;
        }
    }
    
    private synchronized void updateLastRefresh() {
        this.queries = 0;
        this.lastRefresh = System.currentTimeMillis();
    }

    /**
     * Atualiza o registro SPF de um hostname.
     *
     * @throws ProcessException se houver falha no processamento.
     */
    private synchronized void refresh(boolean load,
            boolean bgWhenUnavailable) throws ProcessException {
        long time = System.currentTimeMillis();
        LinkedList<String> registryList = getRegistrySPF(
                hostname, bgWhenUnavailable);
        if (registryList == null) {
            // Domínimo não encontrado.
            this.mechanismList = null;
            this.all = null;
            this.redirect = null;
            this.explanation = null;
            this.error = false;
            CacheSPF.CHANGED = true;
            this.addInexistent();
            updateLastRefresh();
            Server.logLookupSPF(time, hostname, "NXDOMAIN");
        } else if (registryList.isEmpty()) {
            // Sem registro SPF.
            this.mechanismList = new ArrayList<Mechanism>();
            this.all = null;
            this.redirect = null;
            this.explanation = null;
            this.error = false;
            CacheSPF.CHANGED = true;
            this.nxdomain = 0;
            updateLastRefresh();
            Server.logLookupSPF(time, hostname, "NO REGISTRY");
        } else {
            ArrayList<Mechanism> mechanismListIP = new ArrayList<Mechanism>();
            ArrayList<Mechanism> mechanismListDNS = new ArrayList<Mechanism>();
            ArrayList<Mechanism> mechanismListInclude = new ArrayList<Mechanism>();
            ArrayList<Mechanism> mechanismListPTR = new ArrayList<Mechanism>();
            TreeSet<String> visitedTokens = new TreeSet<String>();
            Qualifier allLocal = null;
            String redirectLocal = null;
            String explanationLocal = null;
            boolean errorQuery = false;
            String fixed;
            String result = null;
            for (String registry : registryList) {
                boolean errorRegistry = false;
                StringTokenizer tokenizer = new StringTokenizer(registry, " ");
                while (tokenizer.hasMoreTokens()) {
                    String token = tokenizer.nextToken();
                    if (visitedTokens.contains(token)) {
                        // Token já visitado.
                    } else if (token.equals("spf1")) {
                        // Nada deve ser feito.
                    } else if (token.equals("v=spf1")) {
                        // Nada deve ser feito.
                    } else if (token.equals("v=msv1")) {
                        // Nada deve ser feito.
                    } else if (token.equals("+")) {
                        // Ignorar qualificadores isolados.
                    } else if (token.startsWith("t=") && token.length() == 32) {
                        // Nada deve ser feito.
                    } else if (isMechanismAll(token)) {
                        // Não permitir qualificadores permissivos para all.
                        switch (token.charAt(0)) {
                            case '-':
                                allLocal = Qualifier.FAIL;
                                break;
                            case '~':
                                allLocal = Qualifier.SOFTFAIL;
                                break;
                            default:
                                allLocal = Qualifier.NEUTRAL; // Default qualifier or all.
                        }
                    } else if (isMechanismIPv4(token)) {
                        mechanismListIP.add(new MechanismIPv4(token));
                    } else if (isMechanismIPv6(token)) {
                        mechanismListIP.add(new MechanismIPv6(token));
                    } else if (isMechanismA(token)) {
                        mechanismListDNS.add(new MechanismA(token, load));
                    } else if (isMechanismMX(token)) {
                        mechanismListDNS.add(new MechanismMX(token, load));
                    } else if (isMechanismPTR(token)) {
                        mechanismListPTR.add(new MechanismPTR(token));
                    } else if (isMechanismExistis(token)) {
                        mechanismListDNS.add(new MechanismExists(token));
                    } else if (isMechanismInclude(token)) {
                        mechanismListInclude.add(new MechanismInclude(token));
                    } else if (isModifierRedirect(token)) {
                        int index = token.indexOf("=") + 1;
                        redirectLocal = token.substring(index);
                    } else if (isModifierExplanation(token)) {
                        int index = token.indexOf("=") + 1;
                        explanationLocal = token.substring(index);
                    } else if ((fixed = extractIPv4CIDR(token)) != null) {
                        // Tenta recuperar um erro de sintaxe.
                        if (!visitedTokens.contains(token = "ip4:" + fixed)) {
                            mechanismListIP.add(new MechanismIPv4(token));
                        }
                        errorRegistry = true;
                    } else if ((fixed = extractIPv6CIDR(token)) != null) {
                        // Tenta recuperar um erro de sintaxe.
                        if (!visitedTokens.contains(token = "ip4:" + fixed)) {
                            mechanismListIP.add(new MechanismIPv6(token));
                        }
                        errorRegistry = true;
                    } else {
                        // Um erro durante o processamento foi encontrado.
                        Server.logDebug("SPF token not defined: " + token);
                        errorRegistry = true;
                        errorQuery = true;
                    }
                    visitedTokens.add(token);
                }
                if (result == null) {
                    result = (errorRegistry ? "ERR" : "OK") + " \"" + registry + "\"";
                } else {
                    result += (errorRegistry ? "\\nERR" : "\\nOK") + " \"" + registry + "\"";
                }
            }
            // Considerar os mecanismos na ordem crescente
            // de complexidade de processamento.
            ArrayList<Mechanism> mechanismListLocal = new ArrayList<Mechanism>();
            mechanismListLocal.addAll(mechanismListIP);
            mechanismListLocal.addAll(mechanismListDNS);
            mechanismListLocal.addAll(mechanismListInclude);
            mechanismListLocal.addAll(mechanismListPTR);
            // Atribuição dos novos valores.
            this.mechanismList = mechanismListLocal;
            this.all = allLocal;
            this.redirect = redirectLocal;
            this.explanation = explanationLocal;
            this.error = errorQuery;
            CacheSPF.CHANGED = true;
            this.nxdomain = 0;
            updateLastRefresh();
            Server.logLookupSPF(time, hostname, result);
        }
    }

    /**
     * Verifica se o whois é um mecanismo all válido.
     *
     * @param token o whois a ser verificado.
     * @return verdadeiro se o whois é um mecanismo all válido.
     */
    private static boolean isMechanismAll(String token) {
        return Pattern.matches(
                "^(\\+|-|~|\\?)?all$", token.toLowerCase());
    }

    /**
     * Verifica se o whois é um mecanismo ip4 válido.
     *
     * @param token o whois a ser verificado.
     * @return verdadeiro se o whois é um mecanismo ip4 válido.
     */
    private static boolean isMechanismIPv4(String token) {
        return Pattern.matches(
                "^((\\+|-|~|\\?)?ipv?4?:)?"
                + "(([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}"
                + "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])"
                + "(/[0-9]{1,2})?"
                + "$", token.toLowerCase());
    }

    /**
     * Extrai um CIDR de IPv4 válido.
     *
     * @param token o whois a ser verificado.
     * @return um CIDR de IPv4 válido.
     */
    private static String extractIPv4CIDR(String token) {
        Pattern pattern = Pattern.compile(
                "(:|^)((([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}"
                + "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])"
                + "(/[0-9]{1,2})?)$");
        Matcher matcher = pattern.matcher(token.toLowerCase());
        if (matcher.find()) {
            return matcher.group(2);
        } else {
            return null;
        }
    }

    /**
     * Verifica se o whois é um mecanismo ip6 válido.
     *
     * @param token o whois a ser verificado.
     * @return verdadeiro se o whois é um mecanismo ip6 válido.
     */
    private static boolean isMechanismIPv6(String token) {
        return Pattern.matches(
                "^((\\+|-|~|\\?)?ipv?6?:)?"
                + "((([0-9A-Fa-f]{1,4}:){7}([0-9A-Fa-f]{1,4}|:))|"
                + "(([0-9A-Fa-f]{1,4}:){6}(:[0-9A-Fa-f]{1,4}|"
                + "((25[0-5]|2[0-4]d|1dd|[1-9]?d)"
                + "(.(25[0-5]|2[0-4]d|1dd|[1-9]?d)){3})|:))|"
                + "(([0-9A-Fa-f]{1,4}:){5}(((:[0-9A-Fa-f]{1,4}){1,2})|:"
                + "((25[0-5]|2[0-4]d|1dd|[1-9]?d)"
                + "(.(25[0-5]|2[0-4]d|1dd|[1-9]?d)){3})|:))|"
                + "(([0-9A-Fa-f]{1,4}:){4}(((:[0-9A-Fa-f]{1,4}){1,3})|"
                + "((:[0-9A-Fa-f]{1,4})?:((25[0-5]|2[0-4]d|1dd|[1-9]?d)"
                + "(.(25[0-5]|2[0-4]d|1dd|[1-9]?d)){3}))|:))|"
                + "(([0-9A-Fa-f]{1,4}:){3}(((:[0-9A-Fa-f]{1,4}){1,4})|"
                + "((:[0-9A-Fa-f]{1,4}){0,2}:((25[0-5]|2[0-4]d|1dd|[1-9]?d)"
                + "(.(25[0-5]|2[0-4]d|1dd|[1-9]?d)){3}))|:))|"
                + "(([0-9A-Fa-f]{1,4}:){2}(((:[0-9A-Fa-f]{1,4}){1,5})|"
                + "((:[0-9A-Fa-f]{1,4}){0,3}:((25[0-5]|2[0-4]d|1dd|[1-9]?d)"
                + "(.(25[0-5]|2[0-4]d|1dd|[1-9]?d)){3}))|:))|"
                + "(([0-9A-Fa-f]{1,4}:){1}(((:[0-9A-Fa-f]{1,4}){1,6})|"
                + "((:[0-9A-Fa-f]{1,4}){0,4}:((25[0-5]|2[0-4]d|1dd|[1-9]?d)"
                + "(.(25[0-5]|2[0-4]d|1dd|[1-9]?d)){3}))|:))|"
                + "(:(((:[0-9A-Fa-f]{1,4}){1,7})|"
                + "((:[0-9A-Fa-f]{1,4}){0,5}:((25[0-5]|2[0-4]d|1dd|[1-9]?d)"
                + "(.(25[0-5]|2[0-4]d|1dd|[1-9]?d)){3}))|:)))"
                + "(%.+)?(\\/[0-9]{1,3})?"
                + "$", token);
    }

    /**
     * Extrai um CIDR de IPv6 válido.
     *
     * @param token o whois a ser verificado.
     * @return um CIDR de IPv6 válido.
     */
    private static String extractIPv6CIDR(String token) {
        Pattern pattern = Pattern.compile(
                "(:|^)(((([0-9A-Fa-f]{1,4}:){7}([0-9A-Fa-f]{1,4}|:))|"
                + "(([0-9A-Fa-f]{1,4}:){6}(:[0-9A-Fa-f]{1,4}|"
                + "((25[0-5]|2[0-4]d|1dd|[1-9]?d)"
                + "(.(25[0-5]|2[0-4]d|1dd|[1-9]?d)){3})|:))|"
                + "(([0-9A-Fa-f]{1,4}:){5}(((:[0-9A-Fa-f]{1,4}){1,2})|:"
                + "((25[0-5]|2[0-4]d|1dd|[1-9]?d)"
                + "(.(25[0-5]|2[0-4]d|1dd|[1-9]?d)){3})|:))|"
                + "(([0-9A-Fa-f]{1,4}:){4}(((:[0-9A-Fa-f]{1,4}){1,3})|"
                + "((:[0-9A-Fa-f]{1,4})?:((25[0-5]|2[0-4]d|1dd|[1-9]?d)"
                + "(.(25[0-5]|2[0-4]d|1dd|[1-9]?d)){3}))|:))|"
                + "(([0-9A-Fa-f]{1,4}:){3}(((:[0-9A-Fa-f]{1,4}){1,4})|"
                + "((:[0-9A-Fa-f]{1,4}){0,2}:((25[0-5]|2[0-4]d|1dd|[1-9]?d)"
                + "(.(25[0-5]|2[0-4]d|1dd|[1-9]?d)){3}))|:))|"
                + "(([0-9A-Fa-f]{1,4}:){2}(((:[0-9A-Fa-f]{1,4}){1,5})|"
                + "((:[0-9A-Fa-f]{1,4}){0,3}:((25[0-5]|2[0-4]d|1dd|[1-9]?d)"
                + "(.(25[0-5]|2[0-4]d|1dd|[1-9]?d)){3}))|:))|"
                + "(([0-9A-Fa-f]{1,4}:){1}(((:[0-9A-Fa-f]{1,4}){1,6})|"
                + "((:[0-9A-Fa-f]{1,4}){0,4}:((25[0-5]|2[0-4]d|1dd|[1-9]?d)"
                + "(.(25[0-5]|2[0-4]d|1dd|[1-9]?d)){3}))|:))|"
                + "(:(((:[0-9A-Fa-f]{1,4}){1,7})|"
                + "((:[0-9A-Fa-f]{1,4}){0,5}:((25[0-5]|2[0-4]d|1dd|[1-9]?d)"
                + "(.(25[0-5]|2[0-4]d|1dd|[1-9]?d)){3}))|:)))"
                + "(%.+)?(\\/[0-9]{1,3})?)$");
        Matcher matcher = pattern.matcher(token);
        if (matcher.find()) {
            return matcher.group(2);
        } else {
            return null;
        }
    }

    private static String expand(String hostname,
            String ip, String sender, String helo) {
        int index = sender.indexOf('@');
        String local = sender.substring(0, index);
        String domain = sender.substring(index + 1);
        hostname = hostname.replace("%{i}", ip);
        hostname = hostname.replace("%{h}", helo.startsWith(".") ? helo.substring(1) : helo);
        hostname = hostname.replace("%{l}", local);
        hostname = hostname.replace("%{o}", domain);
        hostname = hostname.replace("%{d}", domain);
        hostname = hostname.replace("%{s}", sender.replace('@', '.'));
        hostname = hostname.replace("%{ir}", Subnet.reverse(ip));
        return hostname;
    }

    /**
     * Verifica se o whois é um mecanismo a válido.
     *
     * @param token o whois a ser verificado.
     * @return verdadeiro se o whois é um mecanismo a válido.
     */
    private static boolean isMechanismA(String token) {
        token = expand(token, "127.0.0.1", "sender@domain.tld", "host.domain.tld");
        return Pattern.matches(
                "^"
                + "(\\+|-|~|\\?)?a"
                + "(:(?=.{1,255}$)[0-9A-Za-z_](?:(?:[0-9A-Za-z_]|-){0,61}[0-9A-Za-z_])?(?:\\.[0-9A-Za-z_](?:(?:[0-9A-Za-z_]|-){0,61}[0-9A-Za-z_])?)*\\.?)?"
                + "(/[0-9]{1,2})?(//[0-9]{1,3})?"
                + "$", token.toLowerCase());
    }

    /**
     * Verifica se o whois é um mecanismo mx válido.
     *
     * @param token o whois a ser verificado.
     * @return verdadeiro se o whois é um mecanismo mx válido.
     */
    private static boolean isMechanismMX(String token) {
        token = expand(token, "127.0.0.1", "sender@domain.tld", "host.domain.tld");
        return Pattern.matches(
                "^(\\+|-|~|\\?)?mx"
                + "(:(?=.{1,255}$)[0-9A-Za-z_](?:(?:[0-9A-Za-z_]|-){0,61}[0-9A-Za-z_])?(?:\\.[0-9A-Za-z_](?:(?:[0-9A-Za-z_]|-){0,61}[0-9A-Za-z_])?)*\\.?)?"
                + "(\\.|/[0-9]{1,2})?(//[0-9]{1,3})?"
                + "$", token.toLowerCase());
    }

    /**
     * Verifica se o whois é um mecanismo ptr válido.
     *
     * @param token o whois a ser verificado.
     * @return verdadeiro se o whois é um mecanismo ptr válido.
     */
    private static boolean isMechanismPTR(String token) {
        token = expand(token, "127.0.0.1", "sender@domain.tld", "host.domain.tld");
        return Pattern.matches(
                "^(\\+|-|~|\\?)?ptr"
                + "(:(?=.{1,255}$)[0-9A-Za-z_](?:(?:[0-9A-Za-z_]|-){0,61}[0-9A-Za-z_])?(?:\\.[0-9A-Za-z_](?:(?:[0-9A-Za-z_]|-){0,61}[0-9A-Za-z_])?)*\\.?)?"
                + "$", token.toLowerCase());
    }

    /**
     * Verifica se o whois é um mecanismo existis válido.
     *
     * @param token o whois a ser verificado.
     * @return verdadeiro se o whois é um mecanismo existis válido.
     */
    private static boolean isMechanismExistis(String token) {
        token = expand(token, "127.0.0.1", "sender@domain.tld", "host.domain.tld");
        return Pattern.matches(
                "^(\\+|-|~|\\?)?exists:"
                + "((?=.{1,255}$)[0-9A-Za-z_](?:(?:[0-9A-Za-z_]|-){0,61}[0-9A-Za-z_])?(?:\\.[0-9A-Za-z_](?:(?:[0-9A-Za-z_]|-){0,61}[0-9A-Za-z_])?)*\\.?)"
                + "$", token.toLowerCase());
    }

    /**
     * Verifica se o whois é um mecanismo include válido.
     *
     * @param token o whois a ser verificado.
     * @return verdadeiro se o whois é um mecanismo include válido.
     */
    private static boolean isMechanismInclude(String token) {
        token = expand(token, "127.0.0.1", "sender@domain.tld", "host.domain.tld");
        return Pattern.matches(
                "^(\\+|-|~|\\?)?include:"
                + "(\\.?(?=.{1,255}$)[0-9A-Za-z_](?:(?:[0-9A-Za-z_]|-){0,61}[0-9A-Za-z_])?(?:\\.[0-9A-Za-z_](?:(?:[0-9A-Za-z_]|-){0,61}[0-9A-Za-z_])?)*\\.?)"
                + "$", token.toLowerCase());
    }

    /**
     * Verifica se o whois é um modificador redirect válido.
     *
     * @param token o whois a ser verificado.
     * @return verdadeiro se o whois é um modificador redirect válido.
     */
    private static boolean isModifierRedirect(String token) {
        token = expand(token, "127.0.0.1", "sender@domain.tld", "host.domain.tld");
        return Pattern.matches(
                "^redirect="
                + "((?=.{1,255}$)[0-9A-Za-z_](?:(?:[0-9A-Za-z_]|-){0,61}[0-9A-Za-z_])?(?:\\.[0-9A-Za-z_](?:(?:[0-9A-Za-z_]|-){0,61}[0-9A-Za-z_])?)*\\.?)"
                + "$", token.toLowerCase());
    }

    /**
     * Verifica se o whois é um modificador explanation válido.
     *
     * @param token o whois a ser verificado.
     * @return verdadeiro se o whois é um modificador explanation válido.
     */
    private static boolean isModifierExplanation(String token) {
        token = expand(token, "127.0.0.1", "sender@domain.tld", "host.domain.tld");
        return Pattern.matches(
                "^exp="
                + "((?=.{1,255}$)[0-9A-Za-z_](?:(?:[0-9A-Za-z_]|-){0,61}[0-9A-Za-z_])?(?:\\.[0-9A-Za-z_](?:(?:[0-9A-Za-z_]|-){0,61}[0-9A-Za-z_])?)*\\.?)"
                + "$", token.toLowerCase());
    }

    /**
     * Verifica se o registro atual expirou.
     *
     * @return verdadeiro se o registro atual expirou.
     */
    public boolean isRegistryExpired() {
        int expiredTime = (int) (System.currentTimeMillis() - lastRefresh) / Server.DAY_TIME;
        return expiredTime > REFRESH_TIME;
    }
    
    /**
     * Verifica se o registro atual expirou.
     *
     * @return verdadeiro se o registro atual expirou.
     */
    public boolean isRegistryExpired7() {
        int expiredTime = (int) (System.currentTimeMillis() - lastRefresh) / Server.DAY_TIME * 7;
        return expiredTime > REFRESH_TIME;
    }
    
    /**
     * Verifica se o registro atual expirou.
     *
     * @return verdadeiro se o registro atual expirou.
     */
    public boolean isRegistryExpired14() {
        int expiredTime = (int) (System.currentTimeMillis() - lastRefresh) / Server.DAY_TIME * 14;
        return expiredTime > REFRESH_TIME;
    }

    /**
     * Retorna o resultado SPF para um IP especifico.
     *
     * @param ip o IP a ser verificado.
     * @return o resultado SPF para um IP especifico.
     * @throws ProcessException se houver falha no processamento.
     */
    public String getResult(String ip, String sender, String helo,
            LinkedList<String> logList) throws ProcessException {
        Qualifier qualifier = getQualifier(
                ip, sender, helo, 0,
                new TreeSet<String>(), logList
        );
        if (qualifier == null) {
            return "NONE";
        } else {
            return qualifier.name();
        }
    }
    
    private void logRedirect(String redirect, Qualifier qualifier, LinkedList<String> logList) {
        if (logList != null) {
            logList.add(getHostname() + ":redirect:" + redirect + " => " + (qualifier == null ? "NOT MATCH" : qualifier.name()));
        }
    }
    
    private void logRedirect(String redirect, String message, LinkedList<String> logList) {
        if (logList != null) {
            logList.add(getHostname() + ":redirect:" + redirect + " => " + message);
        }
    }
    
    private void logError(Qualifier qualifier, LinkedList<String> logList) {
        if (logList != null) {
            logList.add(getHostname() + ":error => " + (qualifier == null ? "NOT MATCH" : qualifier.name()));
        }
    }
    
    private void logAll(Qualifier qualifier, LinkedList<String> logList) {
        if (logList != null) {
            logList.add(getHostname() + ":all => " + (qualifier == null ? "NOT MATCH" : qualifier.name()));
        }
    }
    
    private void logMechanism(Mechanism mechanism, Qualifier qualifier, LinkedList<String> logList) {
        if (logList != null) {
            logList.add(getHostname() + ":" + mechanism + " => " + (qualifier == null ? "NOT MATCH" : qualifier.name()));
        }
    }

    /**
     * Retorna o qualificador para uma consulta SPF.
     *
     * @param ip o IP a ser verificado.
     * @param deep a profundiade de navegação da ávore SPF.
     * @param hostVisitedSet o conjunto de hosts visitados.
     * @return o qualificador da consulta SPF.
     * @throws ProcessException se houver falha no processamento.
     */
    private Qualifier getQualifier(String ip, String sender, String helo,
            int deep, TreeSet<String> hostVisitedSet,
            LinkedList<String> logList) throws ProcessException {
        if (deep > 10) {
            return null; // Evita excesso de consultas.
        } else if (hostVisitedSet.contains(getHostname())) {
            return null; // Evita looping infinito.
        } else if (mechanismList == null) {
            throw new ProcessException("ERROR: HOST NOT FOUND");
        } else //            if (redirect == null)
        {
            hostVisitedSet.add(getHostname());
            for (Mechanism mechanism : mechanismList) {
                if (mechanism instanceof MechanismInclude) {
                    try {
                        MechanismInclude include = (MechanismInclude) mechanism;
                        Qualifier qualifier = include.getQualifierSPF(
                                ip, sender, helo, deep + 1, hostVisitedSet, logList);
                        if (qualifier == null) {
                            // Nenhum qualificador foi definido
                            // então continuar busca.
                        } else {
                            return qualifier;
                        }
                    } catch (ProcessException ex) {
                        if (ex.getMessage().equals("ERROR: HOST NOT FOUND")) {
                            // Não foi possível fazer o include.
                            // O hostname mencionado não existe.
                            // Continuar a verificação dos demais mecanismos.
                        } else {
                            throw ex;
                        }
                    }
                } else if (mechanism instanceof MechanismPTR) {
                    if (mechanism.match(ip, sender, helo)) {
                        // Mecanismo PTR só será processado
                        // no primeiro nível da árvore.
                        Qualifier qualifier = mechanism.getQualifier();
                        logMechanism(mechanism, qualifier, logList);
                        return qualifier;
                    } else {
                        logMechanism(mechanism, null, logList);
                    }
                } else if (mechanism.match(ip, sender, helo)) {
                    Qualifier qualifier = mechanism.getQualifier();
                    logMechanism(mechanism, qualifier, logList);
                    return qualifier;
                } else {
                    logMechanism(mechanism, null, logList);
                }
            }
            if (redirect != null) {
//                hostVisitedSet.add(getHostname());
                SPF spf = CacheSPF.get(redirect);
                if (spf == null) {
                    logRedirect(redirect, "NOT FOUND", logList);
                    return null;
                } else {
                    Qualifier qualifier = spf.getQualifier(ip, sender, helo, 0, hostVisitedSet, logList);
                    logRedirect(redirect, qualifier, logList);
                    return qualifier;
                }
            } else if (error) {
//                // Foi encontrado um erro em algum mecanismos
//                // na qual os demais não tiveram macth.
//                throw new ProcessException("ERROR: SPF PARSE");

                // Nova interpretação SPF para erro de sintaxe.
                // Em caso de erro, retornar SOFTFAIL.
                logError(Qualifier.SOFTFAIL, logList);
                return Qualifier.SOFTFAIL;
            } else if (deep > 0) {
                // O mecanismo all só deve ser
                // processado no primeiro nível da árvore.
                return null;
            } else {
                // Retorna o qualificador do mecanismo all.
                // Pode ser nulo caso o registro não apresente o mecanismo all.
                logAll(all, logList);
                return all;
            }
//        } else {
//            hostVisitedSet.add(getHostname());
//            SPF spf = CacheSPF.get(redirect);
//            return spf.getQualifier(ip, sender, helo, 0, hostVisitedSet);
        }
    }

    /**
     * Retorna o hostname do registro SPF.
     *
     * @return o hostname do registro SPF.
     */
    public String getHostname() {
        return hostname;
    }

    /**
     * Retorna o dominio de explicação do registro SPF. Não sei ainda do que se
     * trata.
     *
     * @return o dominio de explicação do registro SPF.
     */
    public String getExplanation() {
        return explanation;
    }

    /**
     * A enumeração que representa todos os qualificadores possíveis.
     */
    private enum Qualifier {

        PASS("Pass"),
        FAIL("Fail"),
        SOFTFAIL("SoftFail"),
        NEUTRAL("Neutral");
        private final String description;

        private Qualifier(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    /**
     * Classe abstrata que representa qualquer mecanismo de processamento SPF.
     */
    private abstract class Mechanism implements Serializable {

        private static final long serialVersionUID = 1L;
        private final String expression;
        private final Qualifier qualifier;

        private Mechanism(String expression) {
            this.expression = expression;
            switch (expression.charAt(0)) {
                case '+':
                    this.qualifier = Qualifier.PASS;
                    break;
                case '-':
                    this.qualifier = Qualifier.FAIL;
                    break;
                case '~':
                    this.qualifier = Qualifier.SOFTFAIL;
                    break;
                case '?':
                    this.qualifier = Qualifier.NEUTRAL;
                    break;
                default:
                    this.qualifier = Qualifier.PASS; // Default qualifier.
            }
        }

        public abstract boolean match(String ip,
                String sender, String helo) throws ProcessException;

        public Qualifier getQualifier() {
            return qualifier;
        }

        public String getExpression() {
            return expression;
        }

        public boolean equals(Mechanism other) {
            if (other == null) {
                return false;
            } else if (this.qualifier != other.qualifier) {
                return false;
            } else {
                return this.expression.equals(other.expression);
            }
        }

        @Override
        public String toString() {
            return expression;
        }
    }

    /**
     * Mecanismo de processamento CIDR de IPv4.
     */
    private final class MechanismIPv4 extends Mechanism {

        private static final long serialVersionUID = 1L;
        private final int address;
        private final int mask;
        /**
         * Marcado sempre que o mecanismo aponta para blocos reservados.
         */
        private final boolean reserved;

        public MechanismIPv4(String expression) {
            super(expression);
            int index = expression.indexOf(':');
            String inetnum = expression.substring(index + 1);
            index = inetnum.indexOf('/');
            int addressLocal;
            int maskLocal;
            if (index == -1) {
                maskLocal = 0xFFFFFFFF;
                addressLocal = SubnetIPv4.getAddressIP(inetnum);
            } else {
                maskLocal = SubnetIPv4.getMaskNet(inetnum.substring(index + 1));
                addressLocal = SubnetIPv4.getAddressIP(inetnum.substring(0, index)) & maskLocal;
            }
            // Verifica se o endereço pertence a blocos reservados.
            boolean reservedLocal = addressLocal == 0xFFFFFFFF; // Broadcast
            reservedLocal = reservedLocal || (addressLocal & 0xFF000000) == 0x00000000; // RFC 1700 Rede corrente
            reservedLocal = reservedLocal || (addressLocal & 0xFF000000) == 0x0A000000; // RFC 1918 Rede Privada
            reservedLocal = reservedLocal || (addressLocal & 0xFF000000) == 0x0E000000; // RFC 1700 Rede Pública
            reservedLocal = reservedLocal || (addressLocal & 0xFF000000) == 0x27000000; // RFC 1797 Reservado
            reservedLocal = reservedLocal || (addressLocal & 0xFF000000) == 0x7F000000; // RFC 3330 Localhost
            reservedLocal = reservedLocal || (addressLocal & 0xFFFF0000) == 0x80000000; // RFC 3330 Reservado (IANA)
            reservedLocal = reservedLocal || (addressLocal & 0xFFFF0000) == 0xA9FE0000; // RFC 3927 Zeroconf
            reservedLocal = reservedLocal || (addressLocal & 0xFFF00000) == 0xAC100000; // RFC 1918 Rede privada
            reservedLocal = reservedLocal || (addressLocal & 0xFFFF0000) == 0xBFFF0000; // RFC 3330 Reservado (IANA)
            reservedLocal = reservedLocal || (addressLocal & 0xFFFFFF00) == 0xC0000200; // RFC 3330 Documentação
            reservedLocal = reservedLocal || (addressLocal & 0xFFFFFF00) == 0xC0586300; // RFC 3068 IPv6 para IPv4
            reservedLocal = reservedLocal || (addressLocal & 0xFFFF0000) == 0xC0A80000; // RFC 1918 Rede Privada
            reservedLocal = reservedLocal || (addressLocal & 0xFFFE0000) == 0xC6120000; // RFC 2544 Teste de benchmark de redes
            reservedLocal = reservedLocal || (addressLocal & 0xFFFFFF00) == 0xDFFFFF00; // RFC 3330 Reservado
            reservedLocal = reservedLocal || (addressLocal & 0xF0000000) == 0xE0000000; // RFC 3171 Multicasts (antiga rede Classe D)
            reservedLocal = reservedLocal || (addressLocal & 0xF0000000) == 0xF0000000; // RFC 1700 Reservado (antiga rede Classe E)
            // Verifica se algum endereço reservado pertence ao bloco do mecanismo.
            reservedLocal = reservedLocal || addressLocal == (0x0A000000 & maskLocal); // RFC 1918 Rede Privada
            reservedLocal = reservedLocal || addressLocal == (0x0E000000 & maskLocal); // RFC 1700 Rede Pública
            reservedLocal = reservedLocal || addressLocal == (0x27000000 & maskLocal); // RFC 1797 Reservado
            reservedLocal = reservedLocal || addressLocal == (0x7F000000 & maskLocal); // RFC 3330 Localhost
            reservedLocal = reservedLocal || addressLocal == (0x80000000 & maskLocal); // RFC 3330 Reservado (IANA)
            reservedLocal = reservedLocal || addressLocal == (0xA9FE0000 & maskLocal); // RFC 3927 Zeroconf
            reservedLocal = reservedLocal || addressLocal == (0xAC100000 & maskLocal); // RFC 1918 Rede privada
            reservedLocal = reservedLocal || addressLocal == (0xBFFF0000 & maskLocal); // RFC 3330 Reservado (IANA)
            reservedLocal = reservedLocal || addressLocal == (0xC0000200 & maskLocal); // RFC 3330 Documentação
            reservedLocal = reservedLocal || addressLocal == (0xC0586300 & maskLocal); // RFC 3068 IPv6 para IPv4
            reservedLocal = reservedLocal || addressLocal == (0xC0A80000 & maskLocal); // RFC 1918 Rede Privada
            reservedLocal = reservedLocal || addressLocal == (0xC6120000 & maskLocal); // RFC 2544 Teste de benchmark de redes
            reservedLocal = reservedLocal || addressLocal == (0xDFFFFF00 & maskLocal); // RFC 3330 Reservado
            reservedLocal = reservedLocal || addressLocal == (0xE0000000 & maskLocal); // RFC 3171 Multicasts (antiga rede Classe D)
            reservedLocal = reservedLocal || addressLocal == (0xF0000000 & maskLocal); // RFC 1700 Reservado (antiga rede Classe E)
            if (reservedLocal) {
                Server.logDebug("SPF mecanism reserved: " + expression);
            }
            // Associação dos atributos.
            this.address = addressLocal;
            this.mask = maskLocal;
            this.reserved = reservedLocal;
        }
        
        public boolean isReserved() {
            return reserved;
        }

        @Override
        public boolean match(String ip, String sender, String helo) {
            if (isReserved()) {
                // Sempre que estiver apontando para
                // blocos reservados, ignorar o mecanismo.
                return false;
            } else if (SubnetIPv4.isValidIPv4(ip)) {
                int address2 = SubnetIPv4.getAddressIP(ip);
                return address == (address2 & mask);
            } else {
                return false;
            }
        }
    }

    /**
     * Mecanismo de processamento CIDR de IPv6.
     */
    private final class MechanismIPv6 extends Mechanism {

        private static final long serialVersionUID = 1L;
        private final short[] address;
        private final short[] mask;

        public MechanismIPv6(String expression) {
            super(expression);
            String inetnum;
            int index = expression.indexOf(':');
            try {
                String first = expression.substring(0, index);
                Integer.parseInt(first, 16);
                inetnum = expression;
            } catch (NumberFormatException ex) {
                inetnum = expression.substring(index + 1);
            }
            index = inetnum.indexOf('/');
            if (index == -1) {
                this.mask = SubnetIPv6.getMaskIPv6(128);
                this.address = SubnetIPv6.split(inetnum);
            } else {
                this.mask = SubnetIPv6.getMaskIPv6(inetnum.substring(index + 1));
                this.address = SubnetIPv6.split(inetnum.substring(0, index), mask);
            }
        }

        @Override
        public boolean match(String ip, String sender, String helo) {
            if (SubnetIPv6.isValidIPv6(ip)) {
                short[] address2 = SubnetIPv6.split(ip);
                for (int i = 0; i < 8; i++) {
                    if (address[i] != (address2[i] & mask[i])) {
                        return false;
                    }
                }
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Mecanismo de processamento do registro A.
     */
    private final class MechanismA extends Mechanism {

        private static final long serialVersionUID = 1L;
        private final ArrayList<Mechanism> mechanismList = new ArrayList<Mechanism>();
        private boolean loaded = false;
        
        public MechanismA(String expression, boolean load) {
            super(expression);
            if (load && !expression.contains("%")) {
                loadList("127.0.0.1", "sender@domain.tld", "host.domain.tld");
            }
        }
        
        private String getExpression(String ip, String sender, String helo) {
            String expression = getExpression();
            expression = expand(expression, ip, sender, helo);
            if (!Character.isLetter(expression.charAt(0))) {
                // Expressão com qualificador.
                // Extrair qualificador.
                expression = expression.substring(1);
            }
            if (expression.startsWith("a:")) {
                expression = expression.substring(2);
            } else if (expression.startsWith("a")) {
                expression = SPF.this.getHostname() + expression.substring(1);
            }
            return expression;
        }

        private synchronized void loadList(String ip, String sender, String helo) {
            if (!loaded) {
                long time = System.currentTimeMillis();
                // Carregamento de lista.
                String expression = getExpression(ip, sender, helo);
                String hostname = expression;
                String maskIPv4 = null;
                String maskIPv6 = null;
                int indexIPv6Prefix = hostname.indexOf("//");
                if (indexIPv6Prefix != -1) {
                    maskIPv6 = hostname.substring(indexIPv6Prefix + 2);
                    hostname = hostname.substring(0, indexIPv6Prefix);
                }
                int indexIPv4Prefix = hostname.indexOf('/');
                if (indexIPv4Prefix != -1) {
                    maskIPv4 = hostname.substring(indexIPv4Prefix + 1);
                    hostname = hostname.substring(0, indexIPv4Prefix);
                }
                try {
                    TreeSet<String> resultSet = new TreeSet<String>();
                    Attributes attributes = Server.getAttributesDNS(
                            hostname, new String[]{"A"});
                    Attribute attributeA = attributes.get("A");
                    if (attributeA != null) {
                        NamingEnumeration enumeration = attributeA.getAll();
                        while (enumeration.hasMoreElements()) {
                            String hostAddress = (String) enumeration.next();
                            int indexSpace = hostAddress.indexOf(' ') + 1;
                            hostAddress = hostAddress.substring(indexSpace);
                            if (!SubnetIPv4.isValidIPv4(hostAddress)) {
                                try {
                                    hostAddress = InetAddress.getByName(hostAddress).getHostAddress();
                                } catch (UnknownHostException ex) {
                                    // Registro A não encontrado.
                                }
                            }
                            if (maskIPv4 != null) {
                                hostAddress += "/" + maskIPv4;
                            }
                            mechanismList.add(new MechanismIPv4(hostAddress));
                            resultSet.add(hostAddress);
                        }
                    }
                    attributes = Server.getAttributesDNS(
                            hostname, new String[]{"AAAA"});
                    Attribute attributeAAAA = attributes.get("AAAA");
                    if (attributeAAAA != null) {
                        NamingEnumeration enumeration = attributeAAAA.getAll();
                        while (enumeration.hasMoreElements()) {
                            String hostAddress = (String) enumeration.next();
                            int indexSpace = hostAddress.indexOf(' ') + 1;
                            hostAddress = hostAddress.substring(indexSpace);
                            if (Domain.isHostname(hostAddress)) {
                                try {
                                    hostAddress = Inet6Address.getByName(hostAddress).getHostAddress();
                                } catch (UnknownHostException ex) {
                                    // Registro AAAA não encontrado.
                                    hostAddress = null;
                                }
                            }
                            if (SubnetIPv6.isValidIPv6(hostAddress)) {
                                if (maskIPv6 != null) {
                                    hostAddress += "/" + maskIPv6;
                                }
                                mechanismList.add(new MechanismIPv6(hostAddress));
                                resultSet.add(hostAddress);
                            }
                        }
                    }
                    Server.logMecanismA(time, expression, resultSet.toString());
                } catch (CommunicationException ex) {
                    Server.logMecanismA(time, expression, "TIMEOUT");
                } catch (ServiceUnavailableException ex) {
                    Server.logMecanismA(time, expression, "SERVFAIL");
                } catch (NameNotFoundException ex) {
                    Server.logMecanismA(time, expression, "NOT FOUND");
                } catch (InvalidAttributeIdentifierException ex) {
                    Server.logMecanismA(time, expression, "NOT FOUND");
                } catch (NamingException ex) {
                    Server.logMecanismA(time, expression, "ERROR " + ex.getClass() + " " + ex.getMessage());
                }
                if (!expression.contains("%")) {
                    loaded = true;
                }
            }
        }

        @Override
        public boolean match(String ip, String sender, String helo) throws ProcessException {
            loadList(ip, sender, helo);
            for (Mechanism mechanism : mechanismList) {
                if (mechanism.match(ip, sender, helo)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Mecanismo de processamento do registro MX.
     */
    private final class MechanismMX extends Mechanism {

        private static final long serialVersionUID = 1L;
        private final ArrayList<Mechanism> mechanismList = new ArrayList<Mechanism>();
        private boolean loaded = false;
        
        public MechanismMX(String expression, boolean load) {
            super(expression);
            if (load && !expression.contains("%")) {
                loadList("127.0.0.1", "sender@domain.tld", "host.domain.tld");
            }
        }
        
        private String getExpression(String ip, String sender, String helo) {
            String expression = getExpression();
            expression = expand(expression, ip, sender, helo);
            if (!Character.isLetter(expression.charAt(0))) {
                // Expressão com qualificador.
                // Extrair qualificador.
                expression = expression.substring(1);
            }
            if (expression.startsWith("mx:")) {
                expression = expression.substring(3);
            } else if (expression.startsWith("mx")) {
                expression = SPF.this.getHostname() + expression.substring(2);
            }
            return expression;
        }

        private synchronized void loadList(String ip, String sender, String helo) {
            if (!loaded) {
                long time = System.currentTimeMillis();
                // Carregamento de lista.
                String expression = getExpression(ip, sender, helo);
                String hostname = expression;
                String maskIPv4 = null;
                String maskIPv6 = null;
                int indexIPv6Prefix = hostname.indexOf("//");
                if (indexIPv6Prefix != -1) {
                    maskIPv6 = hostname.substring(indexIPv6Prefix + 2);
                    hostname = hostname.substring(0, indexIPv6Prefix);
                }
                int indexIPv4Prefix = hostname.indexOf('/');
                if (indexIPv4Prefix != -1) {
                    maskIPv4 = hostname.substring(indexIPv4Prefix + 1);
                    hostname = hostname.substring(0, indexIPv4Prefix);
                }
                try {
                    TreeSet<String> resultSet = new TreeSet<String>();
                    Attributes attributesMX = Server.getAttributesDNS(
                            hostname, new String[]{"MX"});
                    Attribute attributeMX = attributesMX.get("MX");
                    if (attributeMX != null) {
                        NamingEnumeration enumeration = attributeMX.getAll();
                        while (enumeration.hasMoreElements()) {
                            String hostAddress = (String) enumeration.next();
                            int indexSpace = hostAddress.indexOf(' ') + 1;
                            hostAddress = hostAddress.substring(indexSpace);
                            if (SubnetIPv4.isValidIPv4(hostAddress)) {
                                if (maskIPv4 != null) {
                                    hostAddress += "/" + maskIPv4;
                                }
                                mechanismList.add(new MechanismIPv4(hostAddress));
                                resultSet.add(hostAddress);
                            } else if (SubnetIPv6.isValidIPv6(hostAddress)) {
                                if (maskIPv6 != null) {
                                    hostAddress += "/" + maskIPv6;
                                }
                                mechanismList.add(new MechanismIPv6(hostAddress));
                                resultSet.add(hostAddress);
                            } else {
                                try {
                                    Attributes attributesA = Server.getAttributesDNS(
                                            hostAddress, new String[]{"A"});
                                    Attribute attributeA = attributesA.get("A");
                                    if (attributeA != null) {
                                        for (int i = 0; i < attributeA.size(); i++) {
                                            String host4Address = (String) attributeA.get(i);
                                            if (SubnetIPv4.isValidIPv4(host4Address)) {
                                                if (maskIPv4 != null) {
                                                    host4Address += "/" + maskIPv4;
                                                }
                                                mechanismList.add(new MechanismIPv4(host4Address));
                                                resultSet.add(host4Address);
                                            }
                                        }
                                    }
                                    Attributes attributesAAAA = Server.getAttributesDNS(
                                            hostAddress, new String[]{"AAAA"});
                                    Attribute attributeAAAA = attributesAAAA.get("AAAA");
                                    if (attributeAAAA != null) {
                                        for (int i = 0; i < attributeAAAA.size(); i++) {
                                            String host6Address = (String) attributeAAAA.get(i);
                                            if (SubnetIPv6.isValidIPv6(host6Address)) {
                                                if (maskIPv6 != null) {
                                                    host6Address += "/" + maskIPv6;
                                                }
                                                mechanismList.add(new MechanismIPv6(host6Address));
                                                resultSet.add(host6Address);
                                            }
                                        }
                                    }
                                } catch (NamingException ex) {
                                    // Endereço não encontrado.
                                }
//                                if (indexPrefix == -1) {
//                                    // Se não houver definição CIDR,
//                                    // considerar também os endereços IPv6
//                                    // para ficar compatível com pilha dupla.
//                                    // Isto não é um padrão SPF mas não há
//                                    // prejuízo algum no uso deste conceito.
//                                    try {
//                                        Attributes attributesAAAA = Server.INITIAL_DIR_CONTEXT.getAttributes(
//                                                "dns:/" + hostAddress, new String[]{"AAAA"});
//                                        Attribute attributeAAAA = attributesAAAA.get("AAAA");
//                                        if (attributeAAAA != null) {
//                                            for (int i = 0; i < attributeAAAA.size(); i++) {
//                                                String host6Address = (String) attributeAAAA.get(i);
//                                                if (SubnetIPv6.isValidIPv6(host6Address)) {
//                                                    mechanismList.add(new MechanismIPv6(host6Address));
//                                                    resultSet.add(host6Address);
//                                                }
//                                            }
//                                        }
//                                    } catch (NamingException ex) {
//                                        // Endereço não encontrado.
//                                    }
//                                }
                            }
                        }
                    }
                    Server.logMecanismMX(time, expression, resultSet.toString());
                } catch (CommunicationException ex) {
                    Server.logMecanismMX(time, expression, "TIMEOUT");
                } catch (ServiceUnavailableException ex) {
                    Server.logMecanismMX(time, expression, "SERVFAIL");
                } catch (NameNotFoundException ex) {
                    Server.logMecanismMX(time, expression, "NOT FOUND");
                } catch (InvalidAttributeIdentifierException ex) {
                    Server.logMecanismMX(time, expression, "NOT FOUND");
                } catch (NamingException ex) {
                    Server.logMecanismMX(time, expression, "ERROR " + ex.getClass() + " " + ex.getMessage());
                }
                if (!getExpression().contains("%")) {
                    loaded = true;
                }
            }
        }

        @Override
        public boolean match(String ip, String sender, String helo) throws ProcessException {
            loadList(ip, sender, helo);
            for (Mechanism mechanism : mechanismList) {
                if (mechanism.match(ip, sender, helo)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Mecanismo de processamento do reverso do IP de origem.
     */
    private final class MechanismPTR extends Mechanism {

        private static final long serialVersionUID = 1L;
        
        public MechanismPTR(String expression) {
            super(expression);
        }

        private String getHostname(String ip, String sender, String helo) {
            String expression = getExpression();
            int index = expression.indexOf(':');
            if (index == -1) {
                return SPF.this.getHostname();
            } else {
                expression = expression.substring(index + 1);
                expression = expand(expression, ip, sender, helo);
                return expression;
            }
        }

        @Override
        public synchronized boolean match(
                String ip, String sender,
                String helo) throws ProcessException {
            String hostname = getHostname(ip, sender, helo);
            int index = hostname.indexOf(':');
            if (index > 0) {
                hostname = "." + hostname.substring(index + 1);
            } else {
                hostname = "." + hostname;
            }
            Reverse reverse = Reverse.get(ip);
            if (reverse == null) {
                return false;
            } else {
                for (String address : reverse.getAddressSet()) {
                    if (address.endsWith(hostname)) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    /**
     * Mecanismo de processamento exists.
     */
    private final class MechanismExists extends Mechanism {

        private static final long serialVersionUID = 1L;
        
        public MechanismExists(String expression) {
            super(expression);
        }

        private String getHostname(String ip, String sender, String helo) {
            String expression = getExpression();
            int index = expression.indexOf(':') + 1;
            expression = expression.substring(index);
            expression = expand(expression, ip, sender, helo);
            return expression;
        }

        @Override
        public boolean match(String ip, String sender, String helo) throws ProcessException {
            long time = System.currentTimeMillis();
            String hostname = getHostname(ip, sender, helo);
            try {
                Server.getAttributesDNS(
                        hostname, new String[]{"A"});
                Server.logMecanismA(time, hostname, "EXISTS");
                return true;
            } catch (CommunicationException ex) {
                Server.logMecanismA(time, hostname, "TIMEOUT");
                return false;
            } catch (ServiceUnavailableException ex) {
                Server.logMecanismA(time, hostname, "SERVFAIL");
                return false;
            } catch (NameNotFoundException ex) {
                Server.logMecanismA(time, hostname, "NOT FOUND");
                return false;
            } catch (InvalidAttributeIdentifierException ex) {
                Server.logMecanismA(time, hostname, "NOT FOUND");
                return false;
            } catch (NamingException ex) {
                Server.logMecanismA(time, hostname, "ERROR " + ex.getClass() + " " + ex.getMessage());
                return false;
            }
        }
    }

    /**
     * Mecanismo de inclusão de um nó na árvore SPF.
     */
    private final class MechanismInclude extends Mechanism {

        private static final long serialVersionUID = 1L;

        public MechanismInclude(String expression) {
            super(expression);
        }

        private String getHostname(String ip, String sender, String helo) {
            String expression = getExpression();
            int index = expression.indexOf(':') + 1;
            expression = expression.substring(index);
            expression = expand(expression, ip, sender, helo);
            return expression;
        }

        private Qualifier getQualifierSPF(
                String ip, String sender, String helo,
                int deep, TreeSet<String> hostVisitedSet,
                LinkedList<String> logList) throws ProcessException {
            String hostname = getHostname(ip, sender, helo);
            SPF spf = CacheSPF.get(hostname);
            if (spf == null) {
                return null;
            } else {
                return spf.getQualifier(ip, sender, helo, deep, hostVisitedSet, logList);
            }

        }

        @Override
        public boolean match(String ip, String sender, String helo) throws ProcessException {
            throw new ProcessException("ERROR: FATAL ERROR"); // Não pode fazer o match direto.
        }
    }

    @Override
    public String toString() {
        return hostname + " " + mechanismList + " " + redirect + " " + all;
    }

    /**
     * Classe que representa o cache de registros SPF.
     */
    private static class CacheSPF {

        /**
         * Mapa para cache dos registros SPF consultados.
         */
        private static final HashMap<String,SPF> MAP = new HashMap<String,SPF>();
        /**
         * O próximo registro SPF que deve ser atualizado.
         */
        private static SPF spfRefresh = null;
        /**
         * Flag que indica se o cache foi modificado.
         */
        private static boolean CHANGED = false;
        
        private static boolean isChanged() {
            return CHANGED;
        }
        
        private static void setNotChanged() {
            CHANGED = false;
        }
        
        private static synchronized SPF dropExact(String token) {
            SPF ret = MAP.remove(token);
            if (ret != null) {
                CHANGED = true;
            }
            return ret;
        }

        private static synchronized SPF putExact(String key, SPF value) {
            SPF ret = MAP.put(key, value);
            if (!value.equals(ret)) {
                CHANGED = true;
            }
            return ret;
        }
        
        private static TreeSet<String> keySet() {
            TreeSet<String> keySet = new TreeSet<String>();
            keySet.addAll(MAP.keySet());
            return keySet;
        }
        
        private static HashMap<String,SPF> getMap() {
            HashMap<String,SPF> map = new HashMap<String,SPF>();
            map.putAll(MAP);
            return map;
        }
        
        private static SPF getExact(String host) {
            return MAP.get(host);
        }
        
        private static synchronized Collection<SPF> getValues() {
            return MAP.values();
        }
        
        private static synchronized SPF getRefreshSPF() {
            SPF spf = spfRefresh;
            spfRefresh = null;
            return spf;
        }
        
        private static synchronized void addQuerie(SPF spf) {
            spf.queries++;
            if (spfRefresh == null) {
                spfRefresh = spf;
            } else if (spfRefresh.queries < spf.queries) {
                spfRefresh = spf;
            }
        }
        
        private static void dropExpired() {
            for (String host : keySet()) {
                long time = System.currentTimeMillis();
                SPF spf = getExact(host);
                if (spf != null && spf.isRegistryExpired14()) {
                    spf = dropExact(host);
                    if (spf != null) {
                        Server.logLookupSPF(time, host, "EXPIRED");
                    }
                }
            }
        }

        /**
         * Adiciona um registro SPF no mapa de cache.
         *
         * @param spf o registro SPF para ser adocionado.
         */
        private static void add(SPF spf) {
            putExact(spf.getHostname(), spf);
        }

        private static boolean refresh(String address,
                boolean load) throws ProcessException {
            String host = Domain.extractHost(address, false);
            if (host == null) {
                return false;
            } else {
                SPF spf = getExact(host);
                if (spf == null) {
                    if (load) {
                        spf = new SPF(host);
                        add(spf);
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    spf.refresh(load, false);
                    return true;
                }
            }
        }

        /**
         * Retorna o registro SPF do e-mail.
         *
         * @param address o endereço de e-mail que deve ser consultado.
         * @return o registro SPF, se for encontrado.
         * @throws ProcessException se houver falha no processamento.
         */
        private static SPF get(String address) throws ProcessException {
            String host = Domain.extractHost(address, false);
            if (host == null) {
                return null;
            } else {
                SPF spf = getExact(host);
                if (spf == null) {
                    spf = new SPF(host);
                    add(spf);
                } else if (spf.isRegistryExpired()) {
                    try {
                        // Atualiza o registro se ele for antigo demais.
                        spf.refresh(false, false);
                    } catch (ProcessException ex) {
                        if (ex.getMessage().equals("ERROR: DNS UNAVAILABLE")) {
                            // Manter registro anterior quando houver erro de DNS.
                            Server.logDebug(address + ": SPF temporarily unavailable.");
                        } else {
                            throw ex;
                        }
                    }
                }
//                spf.queries++; // Incrementa o contador de consultas.
                addQuerie(spf); // Incrementa o contador de consultas.
                return spf;
            }
        }

        private static void store() {
            if (isChanged()) {
                try {
                    long time = System.currentTimeMillis();
                    File file = new File("./data/spf.map");
                    HashMap<String,SPF> map = getMap();
                    FileOutputStream outputStream = new FileOutputStream(file);
                    try {
                        SerializationUtils.serialize(map, outputStream);
                        setNotChanged();
                    } finally {
                        outputStream.close();
                    }
                    Server.logStore(time, file);
                } catch (Exception ex) {
                    Server.logError(ex);
                }
            }
        }

        private static void load() {
            long time = System.currentTimeMillis();
            File file = new File("./data/spf.map");
            if (file.exists()) {
                try {
                    HashMap<String,Object> map;
                    FileInputStream fileInputStream = new FileInputStream(file);
                    try {
                        map = SerializationUtils.deserialize(fileInputStream);
                    } finally {
                        fileInputStream.close();
                    }
                    for (String key : map.keySet()) {
                        Object value = map.get(key);
                        if (value instanceof SPF) {
                            SPF spf = (SPF) value;
                            if (!spf.isRegistryExpired14()) {
                                putExact(key, spf);
                            }
                        }
                    }
                    setNotChanged();
                    Server.logLoad(time, file);
                } catch (Exception ex) {
                    Server.logError(ex);
                }
            }
        }
        
        /**
         * Atualiza o registro mais consultado.
         */
        private static void refresh() {
            SPF spfMax = getRefreshSPF();
            if (spfMax == null) {
                for (SPF spf : getValues()) {
                    if (spfMax == null) {
                        spfMax = spf;
                    } else if (spfMax.queries < spf.queries) {
                        spfMax = spf;
                    }
                }
            }
            if (spfMax != null && spfMax.queries > 3) {
                try {
                    spfMax.refresh(true, false);
                } catch (ProcessException ex) {
                    spfMax.updateLastRefresh();
                    if (ex.getMessage().equals("ERROR: HOST NOT FOUND")) {
                        Server.logDebug(spfMax.getHostname() + ": SPF registry cache removed.");
                    } else if (ex.getMessage().equals("ERROR: DNS UNAVAILABLE")) {
                        // Manter registro anterior quando houver erro de DNS.
                        Server.logDebug(spfMax.getHostname() + ": SPF temporarily unavailable.");
                    } else {
                        Server.logError(ex);
                    }
                }
            }
        }
    }
    
    public static void dropExpiredSPF() {
        CacheSPF.dropExpired();
    }
    
    public static void refreshSPF() {
        CacheSPF.refresh();
    }
    
    public static void refreshHELO() {
        CacheHELO.refresh();
    }
    
    /**
     * Classe que representa o cache de registros de denúncia.
     */
    private static class CacheComplain {

        /**
         * Mapa de reclamações com seus respectivos tickets.
         */
        private static final TreeMap<Long,String> MAP = new TreeMap<Long,String>();
        
        /**
         * Flag que indica se o cache de reclamações foi modificado.
         */
        private static boolean CHANGED = false;
        
        private static synchronized boolean dropExact(long time) {
            if (MAP.remove(time) == null) {
                return false;
            } else {
                CHANGED = true;
                return true;
            }
        }
        
        private static synchronized boolean putExact(long time, String tokens) {
            if (tokens == null) {
                return false;
            } else if (tokens.equals(MAP.put(time, tokens))) {
                return false;
            } else {
                CHANGED = true;
                return true;
            }
        }
        
        private static synchronized TreeMap<Long,String> getMap() {
            TreeMap<Long,String> map = new TreeMap<Long,String>();
            map.putAll(MAP);
            return map;
        }
        
        private static synchronized TreeMap<Long,String> headMap(long end) {
            TreeMap<Long,String> head = new TreeMap<Long,String>();
            head.putAll(MAP.headMap(end));
            return head;
        }
        
        private static TreeMap<Long,String> extractHeadMap(long end) {
            TreeMap<Long,String> map = new TreeMap<Long,String>();
            TreeMap<Long,String> head = headMap(end);
            for (long time : head.keySet()) {
                String tokens = head.get(time);
                if (dropExact(time)) {
                    map.put(time, tokens);
                }
            }
            return map;
        }

        private static void store() {
            if (CHANGED) {
                try {
                    long time = System.currentTimeMillis();
                    File file = new File("./data/complain.map");
                    TreeMap<Long,String> map = getMap();
                    FileOutputStream outputStream = new FileOutputStream(file);
                    try {
                        SerializationUtils.serialize(map, outputStream);
                        CHANGED = false;
                    } finally {
                        outputStream.close();
                    }
                    Server.logStore(time, file);
                } catch (Exception ex) {
                    Server.logError(ex);
                }
            }
        }

        private static void load() {
            long time = System.currentTimeMillis();
            File file = new File("./data/complain.map");
            if (file.exists()) {
                try {
                    Map<Long,String> map;
                    FileInputStream fileInputStream = new FileInputStream(file);
                    try {
                        map = SerializationUtils.deserialize(fileInputStream);
                    } finally {
                        fileInputStream.close();
                    }
//                    long end = System.currentTimeMillis() - 604800000;
                    for (Long date : map.keySet()) {
//                        if (date > end) {
                            String tokens = map.get(date);
//                            TreeSet<String> tokenSet = new TreeSet<String>();
//                            StringTokenizer tokenizer = new StringTokenizer(tokens, " ");
//                            while (tokenizer.hasMoreTokens()) {
//                                String token = tokenizer.nextToken();
//                                if (isValid(token)) {
//                                    tokenSet.add(token);
//                                }
//                            }
//                            for (String key : expandTokenSet(tokenSet)) {
//                                if (!Ignore.contains(key)) {
//                                    Distribution distribution = CacheDistribution.get(key, true);
//                                    distribution.addSpamInterval();
//                                    distribution.getStatus(key);
//                                }
//                            }
                            putExact(date, tokens);
//                        }
                    }
                    CHANGED = false;
                    Server.logLoad(time, file);
                } catch (Exception ex) {
                    Server.logError(ex);
                }
            }
        }
        
        private static void dropExpired() {
//            String client = null;
            long end = System.currentTimeMillis() - 604800000;
            TreeMap<Long,String> map = extractHeadMap(end);
            for (long time : map.keySet()) {
                String tokens = map.get(time);
                TreeSet<String> tokenSet = new TreeSet<String>();
                StringTokenizer tokenizer = new StringTokenizer(tokens, " ");
                while (tokenizer.hasMoreTokens()) {
                    String token = tokenizer.nextToken();
                    if (isValid(token)) {
                        tokenSet.add(token);
                    }
                }
                for (String key : expandTokenSet(tokenSet)) {
                    Distribution distribution = CacheDistribution.get(key, false);
                    if (distribution != null && distribution.removeSpam()) {
                        Peer.sendToAll(key, distribution);
                    }
                }
//                time += 604800000;
//                Server.log(time, "CLEAR", client, tokenSet);
            }
        }
        
        private static boolean isValid(String token) {
            if (token == null || token.length() == 0) {
                return false;
            } else if (Subnet.isValidIP(token)) {
                return true;
            } else if (token.startsWith(".") && Domain.isHostname(token.substring(1))) {
                return true;
            } else if (token.contains("@") && Domain.isEmail(token)) {
                return true;
            } else if (token.startsWith("@") && Domain.containsDomain(token.substring(1))) {
                return true;
            } else {
                return false;
            }
        }
        
        public static String getRecipient(String ticket) throws ProcessException {
            if (ticket == null) {
                return null;
            } else {
                String registry = Server.decrypt(ticket);
                int index = registry.indexOf(' ');
                Date date = getTicketDate(registry.substring(0, index));
                if (System.currentTimeMillis() - date.getTime() > 432000000) {
                    // Ticket vencido com mais de 5 dias.
                    throw new ProcessException("TICKET EXPIRED");
                } else {
                    registry = registry.substring(index + 1);
                    StringTokenizer tokenizer = new StringTokenizer(registry, " ");
                    while (tokenizer.hasMoreTokens()) {
                        String token = tokenizer.nextToken();
                        if (token.startsWith(">") && Domain.isEmail(token.substring(1))) {
                            return token.substring(1);
                        }
                    }
                    return null;
                }
            }
        }
        
        public static String getClient(String ticket) throws ProcessException {
            if (ticket == null) {
                return null;
            } else {
                String registry = Server.decrypt(ticket);
                int index = registry.indexOf(' ');
                Date date = getTicketDate(registry.substring(0, index));
                if (System.currentTimeMillis() - date.getTime() > 432000000) {
                    // Ticket vencido com mais de 5 dias.
                    throw new ProcessException("TICKET EXPIRED");
                } else {
                    registry = registry.substring(index + 1);
                    StringTokenizer tokenizer = new StringTokenizer(registry, " ");
                    while (tokenizer.hasMoreTokens()) {
                        String token = tokenizer.nextToken();
                        if (token.endsWith(":")) {
                            int end = token.length() - 1;
                            token = token.substring(0, end);
                            if (Domain.isEmail(token)) {
                                return token;
                            }
                        }
                    }
                    return null;
                }
            }
        }
        
        public static String getSender(String ticket) throws ProcessException {
            if (ticket == null) {
                return null;
            } else {
                String registry = Server.decrypt(ticket);
                int index = registry.indexOf(' ');
                Date date = getTicketDate(registry.substring(0, index));
                if (System.currentTimeMillis() - date.getTime() > 432000000) {
                    // Ticket vencido com mais de 5 dias.
                    throw new ProcessException("TICKET EXPIRED");
                } else {
                    registry = registry.substring(index + 1);
                    StringTokenizer tokenizer = new StringTokenizer(registry, " ");
                    while (tokenizer.hasMoreTokens()) {
                        String token = tokenizer.nextToken();
                        if (token.startsWith("@") && Domain.isHostname(token.substring(1))) {
                            return token;
                        } else if (Domain.isEmail(token)) {
                            return token;
                        }
                    }
                    return null;
                }
            }
        }
        
        public static TreeSet<String> getTokenSet(String ticket) throws ProcessException {
            String registry = Server.decrypt(ticket);
            int index = registry.indexOf(' ');
            Date date = getTicketDate(registry.substring(0, index));
            if (System.currentTimeMillis() - date.getTime() > 432000000) {
                // Ticket vencido com mais de 5 dias.
                throw new ProcessException("TICKET EXPIRED");
            } else {
                TreeSet<String> tokenSet = new TreeSet<String>();
                registry = registry.substring(index + 1);
                StringTokenizer tokenizer = new StringTokenizer(registry, " ");
                while (tokenizer.hasMoreTokens()) {
                    String token = tokenizer.nextToken();
                    if (isValid(token)) {
                        tokenSet.add(token);
                    }
                }
                return tokenSet;
            }
        }

        /**
         * Adiciona uma nova reclamação de SPAM.
         *
         * @param ticket o ticket da mensagem original.
         * @throws ProcessException se houver falha no processamento do ticket.
         */
        public static TreeSet<String> addComplain(String origin,
                String ticket) throws ProcessException {
            if (ticket == null) {
                return null;
            } else {
                long time = System.currentTimeMillis();
                TreeSet<String> tokenSet = new TreeSet<String>();
                TreeSet<String> blackSet = new TreeSet<String>();
                String registry = Server.decrypt(ticket);
                int index = registry.indexOf(' ');
                Date date = getTicketDate(registry.substring(0, index));
                if (System.currentTimeMillis() - date.getTime() > 432000000) {
                    // Ticket vencido com mais de 5 dias.
                    throw new ProcessException("TICKET EXPIRED");
                } else {
                    registry = registry.substring(index + 1);
                    StringTokenizer tokenizer = new StringTokenizer(registry, " ");
                    StringBuilder builder = new StringBuilder();
                    String recipient = null;
                    while (tokenizer.hasMoreTokens()) {
                        String token = tokenizer.nextToken();
                        if (isValid(token)) {
                            tokenSet.add(token);
                            if (builder.length() > 0) {
                                builder.append(' ');
                            }
                            builder.append(token);
                        } else if (token.startsWith(">") && Domain.isEmail(token.substring(1))) {
                            recipient = token.substring(1);
                        }
                    }
                    if (putExact(date.getTime(), builder.toString())) {
                        for (String key : expandTokenSet(tokenSet)) {
                            if (!Ignore.contains(key)) {
                                Distribution distribution = CacheDistribution.get(key, true);
                                if (distribution.addSpam()) {
                                    Peer.sendToAll(key, distribution);
                                }
                                blackSet.add(key);
                                
                            }
                        }
                    } else {
                        // Ticket já denunciado.
                        return null;
                    }
                    Server.log(time, Core.Level.DEBUG, "CMPLN", origin, ticket, blackSet, recipient);
                    return blackSet;
                }
            }
        }
        
        public static TreeSet<String> getComplain(
                String ticket) throws ProcessException {
            if (ticket == null) {
                return null;
            } else {
                TreeSet<String> tokenSet = new TreeSet<String>();
                TreeSet<String> blackSet = new TreeSet<String>();
                String registry = Server.decrypt(ticket);
                int index = registry.indexOf(' ');
                registry = registry.substring(index + 1);
                StringTokenizer tokenizer = new StringTokenizer(registry, " ");
                while (tokenizer.hasMoreTokens()) {
                    String token = tokenizer.nextToken();
                    if (isValid(token)) {
                        tokenSet.add(token);
                    }
                }
                for (String key : expandTokenSet(tokenSet)) {
                    if (!Ignore.contains(key)) {
                        blackSet.add(key);
                    }
                }
                return blackSet;
            }
        }

        /**
         * Remove uma nova reclamação de SPAM.
         *
         * @param ticket o ticket da mensagem original.
         * @throws ProcessException se houver falha no processamento do ticket.
         */
        public static TreeSet<String> deleteComplain(
                String origin, String ticket) throws ProcessException {
            if (ticket == null) {
                return null;
            } else {
                long time = System.currentTimeMillis();
                TreeSet<String> tokenSet = new TreeSet<String>();
                String registry = Server.decrypt(ticket);
                int index = registry.indexOf(' ');
                Date date = getTicketDate(registry.substring(0, index));
                if (dropExact(date.getTime())) {
                    registry = registry.substring(index + 1);
                    StringTokenizer tokenizer = new StringTokenizer(registry, " ");
                    while (tokenizer.hasMoreTokens()) {
                        String token = tokenizer.nextToken();
                        if (isValid(token)) {
                            tokenSet.add(token);
                        }
                    }
                    for (String key : expandTokenSet(tokenSet)) {
                        Distribution distribution = CacheDistribution.get(key, false);
                        if (distribution != null && distribution.removeSpam()) {
                            Peer.sendToAll(key, distribution);
                        }
                    }
                    Server.logQuery(time, "CLEAR", origin, tokenSet);
                    return tokenSet;
                } else {
                    // Ticket não foi denunciado.
                    return null;
                }
            }
        }
    }
    
    public static void dropExpiredComplain() {
        CacheComplain.dropExpired();
    }
    
    public static String getRecipient(String ticket) throws ProcessException {
        return CacheComplain.getRecipient(ticket);
    }
    
    public static String getClient(String ticket) throws ProcessException {
        return CacheComplain.getClient(ticket);
    }
    
    public static String getSender(String ticket) throws ProcessException {
        return CacheComplain.getSender(ticket);
    }
    
    public static TreeSet<String> addComplain(String origin,
                String ticket) throws ProcessException {
        return CacheComplain.addComplain(origin, ticket);
    }
    
    public static TreeSet<String> getComplain(
            String ticket) throws ProcessException {
        return CacheComplain.getComplain(ticket);
    }
    
    public static TreeSet<String> getTokenSet(
            String ticket) throws ProcessException {
        return CacheComplain.getTokenSet(ticket);
    }
    
    public static TreeSet<String> deleteComplain(String origin,
                String ticket) throws ProcessException {
        return CacheComplain.deleteComplain(origin, ticket);
    }

    /**
     * Classe que representa o cache de registros de distribuição de
     * responsáveis.
     */
    private static class CacheDistribution {

        /**
         * Mapa de distribuição binomial dos tokens encontrados.
         */
        private static final TreeMap<String,Distribution> MAP = new TreeMap<String,Distribution>();
        /**
         * Flag que indica se o cache foi modificado.
         */
        private static boolean CHANGED = false;
        
        private static synchronized Distribution dropExact(String key) {
            Distribution ret = MAP.remove(key);
            if (ret != null) {
                CHANGED = true;
            }
            return ret;
        }

        private static synchronized Distribution putExact(String key, Distribution value) {
            Distribution ret = MAP.put(key, value);
            if (!value.equals(ret)) {
                CHANGED = true;
            }
            return ret;
        }
        
        private static synchronized TreeSet<String> keySet() {
            TreeSet<String> keySet = new TreeSet<String>();
            keySet.addAll(MAP.keySet());
            return keySet;
        }
        
        private static synchronized HashMap<String,Distribution> getMap() {
            HashMap<String,Distribution> map = new HashMap<String,Distribution>();
            map.putAll(MAP);
            return map;
        }
        
        private static synchronized NavigableMap<String,Distribution> getSubMap(
                String fromKey, String toKey) {
            return MAP.subMap(fromKey, false, toKey, false);
        }
        
        private static Distribution getExact(String host) {
            return MAP.get(host);
        }
        
        private static boolean isChanged() {
            return CHANGED;
        }
        
        private static void setStored() {
            CHANGED = false;
        }
        
        private static void setLoaded() {
            CHANGED = false;
        }

        private static void store() {
            if (isChanged()) {
                try {
                    long time = System.currentTimeMillis();
                    File file = new File("./data/distribution.map");
                    HashMap<String,Distribution> map = getMap();
                    FileOutputStream outputStream = new FileOutputStream(file);
                    try {
                        SerializationUtils.serialize(map, outputStream);
                        setStored();
                    } finally {
                        outputStream.close();
                    }
                    Server.logStore(time, file);
                } catch (Exception ex) {
                    Server.logError(ex);
                }
            }
        }

        private static void load() {
            long time = System.currentTimeMillis();
            File file = new File("./data/distribution.map");
            if (file.exists()) {
                try {
                    Map<String,Object> map;
                    FileInputStream fileInputStream = new FileInputStream(file);
                    try {
                        map = SerializationUtils.deserialize(fileInputStream);
                    } finally {
                        fileInputStream.close();
                    }
                    for (String key : map.keySet()) {
                        Object value = map.get(key);
                        if (value instanceof Distribution) {
                            Distribution distribution = (Distribution) value;
//                            if (!distribution.isExpired14()) {
//                                distribution.getStatus(key);
//                                distribution.resetSpamInterval();
                                putExact(key.toLowerCase(), distribution);
//                            }
                        }
                    }
                    setLoaded();
                    Server.logLoad(time, file);
                } catch (Exception ex) {
                    Server.logError(ex);
                }
            }
        }

        private static void dropExpired() {
            TreeSet<String> distributionKeySet = new TreeSet<String>();
            distributionKeySet.addAll(keySet());
            for (String token : distributionKeySet) {
                long time = System.currentTimeMillis();
                Distribution distribution = getExact(token);
                if (distribution != null
                        && distribution.hasLastQuery()
                        && distribution.isExpired14()) {
                    distribution = drop(token);
                    if (distribution != null) {
                        Server.log(time, Core.Level.DEBUG, "REPTN", token, "EXPIRED");
                    }
                }
            }
        }

        private static Distribution drop(String key) {
            Distribution distribution = dropExact(key);
            if (distribution != null) {
                Block.dropExact(key);
                Peer.sendToAll(key, null);
            }
            return distribution;
        }
        
        private static TreeMap<String,Distribution> getAll(String value) {
            TreeMap<String,Distribution> map = new TreeMap<String,Distribution>();
            NavigableMap<String,Distribution> subMap;
            Distribution distribution;
            if (Subnet.isValidIP(value)) {
                String ip = Subnet.normalizeIP(value);
                distribution = getExact(ip);
                if (distribution != null) {
                    map.put(ip, distribution);
                }
            } else if (Subnet.isValidCIDR(value)) {
                String cidr = Subnet.normalizeCIDR(value);
                subMap = getSubMap("0", ":");
                for (String ip : subMap.keySet()) {
                    if (Subnet.containsIP(cidr, ip)) {
                        distribution = getExact(ip);
                        if (distribution != null) {
                            map.put(ip, distribution);
                        }
                    }
                }
                subMap = getSubMap("a", "g");
                for (String ip : subMap.keySet()) {
                    if (SubnetIPv6.containsIP(cidr, ip)) {
                        distribution = getExact(ip);
                        if (distribution != null) {
                            map.put(ip, distribution);
                        }
                    }
                }
            } else if (value.startsWith(".")) {
                String hostname = value;
                subMap = getSubMap(".", "/");
                for (String key : subMap.keySet()) {
                    if (key.endsWith(hostname)) {
                        distribution = getExact(key);
                        if (distribution != null) {
                            map.put(key, distribution);
                        }
                    }
                }
                subMap = getSubMap("@", "A");
                for (String mx : subMap.keySet()) {
                    String hostKey = '.' + mx.substring(1);
                    if (hostKey.endsWith(hostname)) {
                        distribution = getExact(mx);
                        if (distribution != null) {
                            map.put(mx, distribution);
                        }
                    }
                }
            } else {
                distribution = getExact(value);
                if (distribution != null) {
                    map.put(value, distribution);
                }
            }
            return map;
        }

        /**
         * Retorna uma distribuição binomial do whois informado.
         *
         * @param key o whois cuja distribuição deve ser retornada.
         * @return uma distribuição binomial do whois informado.
         */
        private static Distribution get(String key, boolean create) {
            Distribution distribution = getExact(key);
            if (distribution != null) {
                if (distribution.isExpired7()) {
                    distribution.reset();
                }
            } else if (create) {
                distribution = new Distribution();
                putExact(key, distribution);
            } else {
                distribution = null;
            }
            return distribution;
        }

        private static TreeMap<String,Distribution> getTreeMap() {
            TreeSet<String> keySet = keySet();
            keySet.addAll(Peer.getReputationKeyAllSet());
            TreeMap<String,Distribution> distributionMap = new TreeMap<String,Distribution>();
            for (String key : keySet) {
                Distribution distribution = get(key, true);
                distributionMap.put(key, distribution);
            }
            return distributionMap;
        }
        
        private static TreeMap<String,Distribution> getTreeMapIPv4() {
            TreeMap<String,Distribution> distributionMap = new TreeMap<String,Distribution>();
            for (String key : keySet()) {
                if (SubnetIPv4.isValidIPv4(key)) {
                    Distribution distribution = getExact(key);
                    if (distribution != null) {
                        distributionMap.put(key, distribution);
                    }
                }
            }
            return distributionMap;
        }
        
        private static TreeMap<String,Distribution> getTreeMapIPv6() {
            TreeMap<String,Distribution> distributionMap = new TreeMap<String,Distribution>();
            for (String key : keySet()) {
                if (SubnetIPv6.isValidIPv6(key)) {
                    Distribution distribution = getExact(key);
                    if (distribution != null) {
                        distributionMap.put(key, distribution);
                    }
                }
            }
            return distributionMap;
        }
        
        private static TreeMap<String,Binomial> getTreeMapExtendedCIDR() {
            TreeMap<String,Binomial> binomialMap = new TreeMap<String,Binomial>();
            for (String cidr : Block.getExtendedCIDR()) {
                Binomial binomial = new Binomial(Status.BLOCK);
                binomialMap.put(cidr, binomial);
            }
            for (String key : keySet()) {
                if (SubnetIPv4.isValidIPv4(key)) {
                    String expandedIP = null;
                    Distribution distribution = getExact(key);
                    if (distribution != null) {
                        expandedIP = SubnetIPv4.expandIPv4(key);
                        String floor = binomialMap.floorKey(expandedIP + "/9");
                        if (floor != null && floor.contains(".")) {
                            String cidr = SubnetIPv4.normalizeCIDRv4(floor);
                            if (SubnetIPv4.containsIPv4(cidr, key)) {
                                Binomial binomial = binomialMap.get(floor);
                                binomial.add(key, distribution);
                                distribution = null;
                            }
                        }
                    }
                    if (distribution != null && expandedIP != null) {
                        Binomial binomial = new Binomial(key, distribution);
                        binomialMap.put(expandedIP + "/32", binomial);
                    }
                }
            }
            return binomialMap;
        }

        private static TreeMap<String,Distribution> getMap(TreeSet<String> tokenSet) {
            TreeMap<String, Distribution> distributionMap = new TreeMap<String,Distribution>();
            for (String token : tokenSet) {
                Distribution distribution = getExact(token);
                if (distribution != null) {
                    distributionMap.put(token, distribution);
                }
            }
            return distributionMap;
        }
    }
    
    public static void dropExpiredDistribution() {
        CacheDistribution.dropExpired();
    }
    

    public static TreeMap<String,Distribution> getDistributionMap() {
        return CacheDistribution.getTreeMap();
    }
    
    public static Distribution getDistribution(String token) {
        return CacheDistribution.get(token, false);
    }
    
    public static Distribution getDistribution(String token, boolean create) {
        return CacheDistribution.get(token, create);
    }
    
    public static TreeMap<String,Distribution> getDistributionMapIPv4() {
        return CacheDistribution.getTreeMapIPv4();
    }
    
    public static TreeMap<String,Distribution> getDistributionMapIPv6() {
        return CacheDistribution.getTreeMapIPv6();
    }
    
    public static TreeMap<String,Binomial> getDistributionMapExtendedCIDR() {
        return CacheDistribution.getTreeMapExtendedCIDR();
    }

    public static void dropDistribution(String token) {
        CacheDistribution.drop(token);
    }

    private static boolean matches(String regex, String token) {
        try {
            return Pattern.matches(regex, token);
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean isWHOIS(String token) {
        return matches("^WHOIS(/[a-z-]+)+((=[a-zA-Z0-9@/.-]+)|((<|>)[0-9]+))$", token);
    }

    private static boolean isREGEX(String token) {
        return matches("^REGEX=[^ ]+$", token);
    }
    
    private static boolean isDNSBL(String token) {
        if (token.startsWith("DNSBL=") && token.contains(";")) {
            int index1 = token.indexOf('=');
            int index2 = token.indexOf(';');
            String server = token.substring(index1 + 1, index2);
            String value = token.substring(index2 + 1);
            return Domain.isHostname(server) && Subnet.isValidIP(value);
        } else {
            return false;
        }
    }

    private static boolean isCIDR(String token) {
        return matches("^CIDR=("
                + "((([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])\\.){3}"
                + "([0-9]|[1-9][0-9]|1[0-9]{2}|2[0-4][0-9]|25[0-5])/[0-9]{1,2})"
                + "|"
                + "(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|"
                + "([0-9a-fA-F]{1,4}:){1,7}:|"
                + "([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|"
                + "([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|"
                + "([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|"
                + "([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|"
                + "([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|"
                + "[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|"
                + ":((:[0-9a-fA-F]{1,4}){1,7}|:)|"
                + "fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|"
                + "::(ffff(:0{1,4}){0,1}:){0,1}"
                + "((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\\.){3,3}"
                + "(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|"
                + "([0-9a-fA-F]{1,4}:){1,4}:"
                + "((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\\.){3,3}"
                + "(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])/[0-9]{1,3})"
                + ")$", token);
    }

    private static String normalizeCIDR(String token) {
        if (token == null) {
            return null;
        } else if (token.startsWith("CIDR=")) {
            int index = token.indexOf('=');
            String cidr = token.substring(index + 1);
            return "CIDR=" + Subnet.normalizeCIDR(cidr);
        } else {
            return null;
        }
    }
    
    public static String normalizeTokenFull(String token) throws ProcessException {
        return normalizeToken(token, true, true, true, true ,true);
    }

    public static String normalizeToken(
            String token,
            boolean canWHOIS,
            boolean canREGEX,
            boolean canCIDR,
            boolean canDNSBL,
            boolean canFAIL
            ) throws ProcessException {
        if (token == null || token.length() == 0) {
            return null;
        } else if (canWHOIS && isWHOIS(token)) {
            return token;
        } else if (canREGEX && isREGEX(token)) {
            try {
                int index = token.indexOf('=');
                String regex = token.substring(index + 1);
                Pattern.compile(regex);
                return token;
            } catch (Exception ex) {
                return null;
            }
        } else if (canCIDR && isCIDR(token)) {
            return normalizeCIDR(token);
        } else if (canCIDR && SubnetIPv4.isValidIPv4(token)) {
            return "CIDR=" + SubnetIPv4.normalizeIPv4(token) + "/32";
        } else if (canCIDR && SubnetIPv6.isValidIPv6(token)) {
            return "CIDR=" + SubnetIPv6.normalizeIPv6(token) + "/128";
        } else if (canWHOIS && Owner.isOwnerID(token)) {
            return "WHOIS/ownerid=" + Owner.normalizeID(token);
        } else if (canCIDR && Subnet.isValidCIDR(token)) {
            return "CIDR=" + Subnet.normalizeCIDR(token);
        } else if (canDNSBL && isDNSBL(token)) {
            int index1 = token.indexOf('=');
            int index2 = token.indexOf(';');
            String server = token.substring(index1 + 1, index2);
            String value = token.substring(index2 + 1);
            server = Domain.normalizeHostname(server, false);
            value = Subnet.normalizeIP(value);
            return "DNSBL=" + server + ';' + value;
        } else {
            token = Core.removerAcentuacao(token);
            String recipient = "";
            if (token.contains(">")) {
                int index = token.indexOf('>');
                recipient = token.substring(index + 1);
                token = token.substring(0, index);
                if (Domain.isEmail(recipient)) {
                    recipient = '>' + recipient.toLowerCase();
                } else if (recipient.startsWith("@") && Domain.containsDomain(recipient.substring(1))) {
                    recipient = '>' + recipient.toLowerCase();
                } else {
                    return null;
                }
            }
            String qualif = "";
            if (token.contains(";")) {
                int index = token.indexOf(';');
                qualif = token.substring(index);
                if (qualif.equals(";PASS")) {
                    token = token.substring(0, index);
                } else if (qualif.equals(";SOFTFAIL")) {
                    token = token.substring(0, index);
                } else if (qualif.equals(";NEUTRAL")) {
                    token = token.substring(0, index);
                } else if (qualif.equals(";NONE")) {
                    token = token.substring(0, index);
                } else if (canFAIL && qualif.equals(";FAIL")) {
                    token = token.substring(0, index);
                } else {
                    // Sintaxe com erro.
                    return null;
                }
            }
            if (Domain.isEmail(token)) {
                return token.toLowerCase() + qualif + recipient;
            } else if (token.endsWith("@")) {
                return token.toLowerCase() + qualif + recipient;
            } else if (token.startsWith("@") && Domain.containsDomain(token.substring(1))) {
                return token.toLowerCase() + qualif + recipient;
            } else if (!token.contains("@") && Domain.containsDomain(token)) {
                return Domain.extractHost(token, true) + qualif + recipient;
            } else if (token.startsWith(".") && Domain.containsDomain(token.substring(1))) {
                return Domain.extractHost(token, true) + qualif + recipient;
            } else if (Subnet.isValidIP(token)) {
                return Subnet.normalizeIP(token) + qualif + recipient;
            } else {
                return null;
            }
        }
    }

    public static TreeSet<String> clear(String token) {
        TreeSet<String> clearSet = new TreeSet<String>();
        TreeMap<String,Distribution> distribuitonMap = CacheDistribution.getAll(token);
        for (String key : distribuitonMap.keySet()) {
            Distribution distribution = distribuitonMap.get(key);
            if (distribution != null) {
                clearSet.add(key);
                if (distribution.clear()) {
                    Peer.sendToAll(key, distribution);
                }
            }
            if (Block.dropExact(key)) {
                clearSet.add(key);
            }
        }
        for (String key : Block.getAllTokens(token)) {
            if (Block.dropExact(key)) {
                clearSet.add(key);
            }
        }
        for (String key : Peer.clearAllReputation(token)) {
            clearSet.add(key);
        }
        return clearSet;
    }

    public static TreeSet<String> getGuessSet() throws ProcessException {
        return CacheGuess.get();
    }
    
    public static HashMap<String,String> getGuessMap() throws ProcessException {
        return CacheGuess.getMap();
    }


    /**
     * Classe que representa o cache de "best-guess" exclusivos.
     */
    private static class CacheGuess {

        /**
         * http://www.openspf.org/FAQ/Best_guess_record porém compatível com
         * IPv6
         */
        private static final String BEST_GUESS = "v=spf1 a/24//48 mx/24//48 ptr ?all";
        /**
         * Mapa de registros manuais de SPF caso o domínio não tenha um.
         */
        private static final HashMap<String,String> MAP = new HashMap<String,String>();
        /**
         * Flag que indica se o cache foi modificado.
         */
        private static boolean CHANGED = false;
        
        private static synchronized String dropExact(String token) {
            String ret = MAP.remove(token);
            if (ret == null) {
                return null;
            } else {
                CHANGED = true;
                return ret;
            }
        }

        private static synchronized String putExact(String key, String value) {
            String ret = MAP.put(key, value);
            if (!value.equals(ret)) {
                CHANGED = true;
            }
            return ret;
        }
        
        private static TreeSet<String> keySet() {
            TreeSet<String> keySet = new TreeSet<String>();
            keySet.addAll(MAP.keySet());
            return keySet;
        }
        
        private static HashMap<String,String> getMap() {
            HashMap<String,String> map = new HashMap<String,String>();
            map.putAll(MAP);
            return map;
        }

        private static boolean containsExact(String address) {
            return MAP.containsKey(address);
        }
        
        private static String getExact(String host) {
            return MAP.get(host);
        }
        
        private static boolean isChanged() {
            return CHANGED;
        }
        
        private static void setStored() {
            CHANGED = false;
        }
        
        private static void setLoaded() {
            CHANGED = false;
        }

        private static boolean add(String hostname,
                String spf) throws ProcessException {
            hostname = Domain.extractHost(hostname, false);
            if (!Domain.containsDomain(hostname)) {
                throw new ProcessException("ERROR: HOSTNAME INVALID");
            } else if (!spf.equals(putExact("." + hostname, spf))) {
                CacheSPF.refresh(hostname, true);
                return true;
            } else {
                return false;
            }
        }
        
        private static TreeSet<String> dropAll() throws ProcessException {
            TreeSet<String> guessSet = new TreeSet<String>();
            for (String domain : keySet()) {
                String spf = dropExact(domain);
                if (spf != null) {
                    guessSet.add(domain + " \"" + spf + "\"");
                }
            }
            return guessSet;
        }

        private static boolean drop(String hostname) throws ProcessException {
            hostname = Domain.extractHost(hostname, false);
            if (!Domain.containsDomain(hostname)) {
                throw new ProcessException("ERROR: HOSTNAME INVALID");
            } else if (dropExact("." + hostname) == null) {
                return false;
            } else {
                CacheSPF.refresh(hostname, true);
                return true;
            }
        }

        private static boolean contains(String host) {
            if (!host.startsWith(".")) {
                host = "." + host;
            }
            return containsExact(host);
        }

        private static TreeSet<String> get() throws ProcessException {
            TreeSet<String> guessSet = new TreeSet<String>();
            for (String domain : keySet()) {
                String spf = get(domain);
                guessSet.add(domain + " \"" + spf + "\"");
            }
            return guessSet;
        }

        private static String get(String host) {
            if (!host.startsWith(".")) {
                host = "." + host;
            }
            String guess = getExact(host);
            if (guess == null) {
                // Se não hoouver palpite SPF específico para o hostname,
                // utilizar o palpite padrão, porém adaptado para IPv6.
                // http://www.openspf.org/FAQ/Best_guess_record
                return BEST_GUESS;
            } else {
                // Significa que um palpite SPF específico
                // foi registrado para este hostname.
                // Neste caso utilizar o paltpite específico.
                return guess;
            }
        }

        private static void store() {
            if (isChanged()) {
                try {
                    long time = System.currentTimeMillis();
                    File file = new File("./data/guess.map");
                    HashMap<String,String> map = getMap();
                    FileOutputStream outputStream = new FileOutputStream(file);
                    try {
                        SerializationUtils.serialize(map, outputStream);
                        setStored();
                    } finally {
                        outputStream.close();
                    }
                    Server.logStore(time, file);
                } catch (Exception ex) {
                    Server.logError(ex);
                }
            }
        }

        private static void load() {
            long time = System.currentTimeMillis();
            File file = new File("./data/guess.map");
            if (file.exists()) {
                try {
                    HashMap<String,String> map;
                    FileInputStream fileInputStream = new FileInputStream(file);
                    try {
                        map = SerializationUtils.deserialize(fileInputStream);
                    } finally {
                        fileInputStream.close();
                    }
                    for (String key : map.keySet()) {
                        String value = map.get(key);
                        putExact(key, value);
                    }
                    setLoaded();
                    Server.logLoad(time, file);
                } catch (Exception ex) {
                    Server.logError(ex);
                }
            }
        }
    }

    public static boolean addGuess(String host, String spf) throws ProcessException {
        return CacheGuess.add(host, spf);
    }

    public static boolean dropGuess(String host) throws ProcessException {
        return CacheGuess.drop(host);
    }
    
    public static TreeSet<String> dropAllGuess() throws ProcessException {
        return CacheGuess.dropAll();
    }

    public static void storeGuess() {
        CacheGuess.store();
    }
    
    public static void storeDistribution() {
        CacheDistribution.store();
    }

    public static void storeSPF() {
        CacheSPF.store();
    }

    /**
     * Armazenamento de cache em disco.
     */
    public static void store() {
        CacheSPF.store();
        CacheDistribution.store();
        CacheComplain.store();
        CacheGuess.store();
        CacheHELO.store();
    }

    /**
     * Carregamento de cache do disco.
     */
    public static void load() {
        CacheSPF.load();
        CacheDistribution.load();
        CacheComplain.load();
        CacheGuess.load();
        CacheHELO.load();
    }

    /**
     * Classe que representa o cache de resolução de HELO.
     */
    private static class CacheHELO {

        /**
         * Mapa de atributos da verificação de HELO.
         */
        private static final HashMap<String,HELO> MAP = new HashMap<String,HELO>();
        /**
         * O próximo registro HELO que deve ser atualizado.
         */
        private static String hostRefresh = null;
        private static HELO heloRefresh = null;
        /**
         * Flag que indica se o cache foi modificado.
         */
        private static boolean CHANGED = false;
        
        private static synchronized HELO dropExact(String token) {
            HELO ret = MAP.remove(token);
            if (ret != null) {
                CHANGED = true;
            }
            return ret;
        }

        private static synchronized HELO putExact(String key, HELO value) {
            HELO ret = MAP.put(key, value);
            if (!value.equals(ret)) {
                CHANGED = true;
            }
            return ret;
        }
        
        private static synchronized TreeSet<String> keySet() {
            TreeSet<String> keySet = new TreeSet<String>();
            keySet.addAll(MAP.keySet());
            return keySet;
        }
        
        private static HashMap<String,HELO> getMap() {
            HashMap<String,HELO> map = new HashMap<String,HELO>();
            map.putAll(MAP);
            return map;
        }
        
        private static HELO getExact(String host) {
            return MAP.get(host);
        }
        
        private static synchronized String getRefreshHELO() {
            String helo = hostRefresh;
            hostRefresh = null;
            heloRefresh = null;
            return helo;
        }
        
        private static synchronized void addQuery(String host, HELO helo) {
            helo.queryCount++;
            helo.lastQuery = System.currentTimeMillis();
            if (hostRefresh == null || heloRefresh == null) {
                hostRefresh = host;
                heloRefresh = helo;
            } else if (heloRefresh.queryCount < helo.queryCount) {
                hostRefresh = host;
                heloRefresh = helo;
            }
        }

        /*
         * Classe para guardar os atributos da consulta.
         */
        private static final class HELO implements Serializable {

            private static final long serialVersionUID = 1L;
            
            private Attributes attributes = null; // Obsoleto
            private TreeSet<String> addressSet = null;
            private String address4 = null;
            private String address6 = null;
            private int queryCount = 0;
            private long lastQuery;
            
            private HELO(String hostname) {
                this.lastQuery = System.currentTimeMillis();
                refresh(hostname);
            }

            public synchronized void refresh(String hostname) {
                long time = System.currentTimeMillis();
                try {
                    TreeSet<String> ipv4Set = new TreeSet<String>();
                    TreeSet<String> ipv6Set = new TreeSet<String>();
                    Attributes attributesA = Server.getAttributesDNS(
                            hostname, new String[]{"A"});
                    if (attributesA != null) {
                        Enumeration enumerationA = attributesA.getAll();
                        while (enumerationA.hasMoreElements()) {
                            Attribute attributeA = (Attribute) enumerationA.nextElement();
                            NamingEnumeration enumeration = attributeA.getAll();
                            while (enumeration.hasMoreElements()) {
                                String address = (String) enumeration.next();
                                if (SubnetIPv4.isValidIPv4(address)) {
                                    address = SubnetIPv4.normalizeIPv4(address);
                                    ipv4Set.add(address);
                                }
                            }
                        }
                        Attributes attributesAAAA = Server.getAttributesDNS(
                                hostname, new String[]{"AAAA"});
                        if (attributesAAAA != null) {
                            Enumeration enumerationAAAA = attributesAAAA.getAll();
                            while (enumerationAAAA.hasMoreElements()) {
                                Attribute attributeAAAA = (Attribute) enumerationAAAA.nextElement();
                                NamingEnumeration enumeration = attributeAAAA.getAll();
                                while (enumeration.hasMoreElements()) {
                                    String address = (String) enumeration.next();
                                    if (SubnetIPv6.isValidIPv6(address)) {
                                        address = SubnetIPv6.normalizeIPv6(address);
                                        ipv6Set.add(address);
                                    }
                                }
                            }
                        }
                    }
                    this.addressSet = new TreeSet<String>();
                    this.addressSet.addAll(ipv4Set);
                    this.addressSet.addAll(ipv6Set);
                    if (ipv4Set.size() == 1) {
                        this.address4 = ipv4Set.first();
                    } else {
                        this.address4 = null;
                    }
                    if (ipv6Set.size() == 1) {
                        this.address6 = ipv6Set.first();
                    } else {
                        this.address6 = null;
                    }
                    Server.logLookupHELO(time, hostname, addressSet.toString());
                } catch (CommunicationException ex) {
                    Server.logLookupHELO(time, hostname, "TIMEOUT");
                } catch (ServiceUnavailableException ex) {
                    Server.logLookupHELO(time, hostname, "SERVFAIL");
                } catch (NameNotFoundException ex) {
                    this.addressSet = null;
                    this.address4 = null;
                    this.address6 = null;
                    Server.logLookupHELO(time, hostname, "NXDOMAIN");
                } catch (NamingException ex) {
                    this.addressSet = null;
                    this.address4 = null;
                    this.address6 = null;
                    Server.logLookupHELO(time, hostname, "ERROR " + ex.getClass() + " " + ex.getExplanation());
                } finally {
                    this.attributes = null;
                    this.queryCount = 0;
                    CHANGED = true;
                }
            }
            
            public TreeSet<String> getAddressSet() {
                TreeSet<String> set = new TreeSet<String>();
                set.addAll(this.addressSet);
                return set;
            }
            
            public boolean contains(String ip) {
                if ((ip = Subnet.normalizeIP(ip)) == null) {
                    return false;
                } else if (addressSet == null) {
                    return false;
                } else {
                    return addressSet.contains(ip);
                }
            }
            
            public String getAddress4() {
                return address4;
            }
            
            public String getAddress6() {
                return address6;
            }

            public boolean isExpired7() {
                return System.currentTimeMillis() - lastQuery > 604800000;
            }
            
            public boolean isExpired14() {
                return System.currentTimeMillis() - lastQuery > 1209600000;
            }

            @Deprecated
            private synchronized void update() {
                if (attributes != null) {
                    TreeSet<String> ipv4Set = new TreeSet<String>();
                    TreeSet<String> ipv6Set = new TreeSet<String>();
                    Enumeration enumeration = attributes.getAll();
                    while (enumeration.hasMoreElements()) {
                        try {
                            Attribute attribute = (Attribute) enumeration.nextElement();
                            NamingEnumeration namingEnumeration = attribute.getAll();
                            while (namingEnumeration.hasMoreElements()) {
                                String address = (String) namingEnumeration.next();
                                if (SubnetIPv4.isValidIPv4(address)) {
                                    address = SubnetIPv4.normalizeIPv4(address);
                                    ipv4Set.add(address);
                                } else if (SubnetIPv6.isValidIPv6(address)) {
                                    address = SubnetIPv6.normalizeIPv6(address);
                                    ipv6Set.add(address);
                                }
                            }
                        } catch (NamingException ex) {
                        }
                    }
                    this.addressSet = new TreeSet<String>();
                    this.addressSet.addAll(ipv4Set);
                    this.addressSet.addAll(ipv6Set);
                    if (ipv4Set.size() == 1) {
                        this.address4 = ipv4Set.first();
                    } else {
                        this.address4 = null;
                    }
                    if (ipv6Set.size() == 1) {
                        this.address6 = ipv6Set.first();
                    } else {
                        this.address6 = null;
                    }
                    this.attributes = null;
                }
            }
        }
        
        public static String getUniqueIPv4(String helo) {
            if ((helo = Domain.extractHost(helo, false)) == null) {
                return null;
            } else {
                HELO heloObj = getExact(helo);
                if (heloObj == null) {
                    return null;
                } else {
                    return heloObj.getAddress4();
                }
            }
        }
        
        public static String getUniqueIPv6(String helo) {
            if ((helo = Domain.extractHost(helo, false)) == null) {
                return null;
            } else {
                HELO heloObj = getExact(helo);
                if (heloObj == null) {
                    return null;
                } else {
                    return heloObj.getAddress6();
                }
            }
        }

        public static boolean match(String ip, String helo, boolean refresh) {
            if ((helo = Domain.extractHost(helo, false)) == null) {
                return false;
            } else {
                HELO heloObj = getExact(helo);
                if (heloObj == null) {
                    heloObj = new HELO(helo);
                    putExact(helo, heloObj);
                } else if (refresh) {
                    heloObj.refresh(helo);
                    addQuery(helo, heloObj);
                    CHANGED = true;
                } else {
                    addQuery(helo, heloObj);
                    CHANGED = true;
                }
                return heloObj.contains(ip);
            }
        }

        private static void dropExpired() {
            for (String helo : keySet()) {
                long time = System.currentTimeMillis();
                HELO heloObj = getExact(helo);
                if (heloObj != null && heloObj.isExpired14()) {
                    heloObj = dropExact(helo);
                    if (heloObj != null) {
                        Server.logLookupHELO(time, helo, "EXPIRED");
                    }
                }
            }
        }

        /**
         * Atualiza o registro mais consultado.
         */
        private static void refresh() {
            String heloMax = getRefreshHELO();
            HELO heloObjMax = getExact(heloMax);
            if (heloObjMax == null) {
                for (String hostname : keySet()) {
                    HELO heloObj = getExact(hostname);
                    if (heloObj != null) {
                        if (heloObjMax == null) {
                            heloMax = hostname;
                            heloObjMax = heloObj;
                        } else if (heloObjMax.queryCount < heloObj.queryCount) {
                            heloMax = hostname;
                            heloObjMax = heloObj;
                        }
                    }
                }
            }
            if (heloMax != null && heloObjMax != null && heloObjMax.queryCount > 3) {
                heloObjMax.refresh(heloMax);
            }
        }

        private static void store() {
            if (CHANGED) {
                try {
                    long time = System.currentTimeMillis();
                    File file = new File("./data/helo.map");
                    HashMap<String,HELO> map = getMap();
                    FileOutputStream outputStream = new FileOutputStream(file);
                    try {
                        SerializationUtils.serialize(map, outputStream);
                        CHANGED = false;
                    } finally {
                        outputStream.close();
                    }
                    Server.logStore(time, file);
                } catch (Exception ex) {
                    Server.logError(ex);
                }
            }
        }

        private static void load() {
            long time = System.currentTimeMillis();
            File file = new File("./data/helo.map");
            if (file.exists()) {
                try {
                    HashMap<String,Object> map;
                    FileInputStream fileInputStream = new FileInputStream(file);
                    try {
                        map = SerializationUtils.deserialize(fileInputStream);
                    } finally {
                        fileInputStream.close();
                    }
                    for (String key : map.keySet()) {
                        Object value = map.get(key);
                        if (value instanceof HELO) {
                            HELO helo = (HELO) value;
                            helo.update();
                            putExact(key, helo);
                        }
                    }
                    CHANGED = false;
                    Server.logLoad(time, file);
                } catch (Exception ex) {
                    Server.logError(ex);
                }
            }
        }
    }
    
    public static void dropExpiredHELO() {
        CacheHELO.dropExpired();
    }
    
    public static boolean matchHELO(String ip, String helo, boolean refresh) {
        return CacheHELO.match(ip, helo, refresh);
    }
    
    public static boolean matchHELO(String ip, String helo) {
        return CacheHELO.match(ip, helo, false);
    }

    protected static String processPostfixSPF(
            InetAddress ipAddress,
            Client client,
            User user,
            String ip,
            String sender,
            String helo,
            String recipient
            ) throws ProcessException {
        if (sender == null) {
            sender = null;
        } else if (sender.trim().length() == 0) {
            sender = null;
        } else {
            sender = sender.toLowerCase();
        }
        
        if (!Subnet.isValidIP(ip)) {
            return "action=554 5.7.1 SPFBL "
                    + ip + " is not a valid public IP.\n\n";
        } else if (
                Subnet.containsIP("10.0.0.0/8", ip) ||
                Subnet.containsIP("172.16.0.0/12", ip) ||
                Subnet.containsIP("192.168.0.0/16", ip) ||
                Subnet.containsIP("169.254.0.0/16", ip) ||
                (client != null && client.contains(ip))
                ) {
            // Message from LAN.
            return "action=DUNNO\n\n";
        } else {
            try {
                TreeSet<String> tokenSet = new TreeSet<String>();
                ip = Subnet.normalizeIP(ip);
                tokenSet.add(Subnet.normalizeIP(ip));
                String origin;
                if (client == null) {
                    origin = ipAddress.getHostAddress();
                } else if (client.hasEmail()) {
                    // Se houver um cliente válido,
                    // Adicionar no ticket para controle.
                    tokenSet.add(client.getEmail() + ':');
                    origin = ipAddress.getHostAddress() + " " + client.getDomain() + " " + client.getEmail();
                } else {
                    origin = ipAddress.getHostAddress() + " " + client.getDomain();
                }
                if (Domain.isEmail(recipient)) {
                    // Se houver um remetente válido,
                    // Adicionar no ticket para controle externo.
                    tokenSet.add('>' + recipient);
                } else {
                    recipient = null;
                }
                // Passar a acompanhar todos os 
                // HELO quando apontados para o IP para 
                // uma nova forma de interpretar dados.
                String hostname;
                if (CacheHELO.match(ip, helo, false)) {
                    helo = Domain.normalizeHostname(helo, true);
                    hostname = helo;
                } else {
                    hostname = Reverse.getHostname(ip);
                    hostname = Domain.normalizeHostname(hostname, true);
                }
                if (hostname == null) {
                    Server.logDebug("no reverse for " + ip + ".");
                } else {
                    tokenSet.add(hostname);
                    String ipv4 = CacheHELO.getUniqueIPv4(hostname);
                    if (ipv4 != null) {
                        // Equivalência de pilha dupla se 
                        // IPv4 for único para o hostname.
                        tokenSet.add(ipv4);
                    }
                    String ipv6 = CacheHELO.getUniqueIPv6(hostname);
                    if (ipv6 != null) {
                        // Equivalência de pilha dupla se 
                        // IPv6 for único para o hostname.
                        tokenSet.add(ipv6);
                    }
                }
                String result;
                LinkedList<String> logList = new LinkedList<String>();
                
                SPF spf;
                if (sender == null) {
                    spf = null;
                    result = "NONE";
                } else if (!Domain.isEmail(sender)) {
                    spf = null;
                    result = "NONE";
                } else if (Domain.isReserved(sender)) {
                    spf = null;
                    result = "NONE";
                } else if ((spf = CacheSPF.get(sender)) == null) {
                    result = "NONE";
                } else if (spf.isInexistent()) {
                    result = "NONE";
                } else {
                    result = spf.getResult(ip, sender, helo, logList);
                }
                String origem;
                String fluxo;
                if (result.equals("PASS") || (sender != null && Provider.containsHELO(ip, helo))) {
                    // Quando fo PASS, significa que o domínio
                    // autorizou envio pelo IP, portanto o dono dele
                    // é responsavel pelas mensagens.
                    String mx = Domain.extractHost(sender, true);
                    if (Provider.containsExact(mx)) {
                        // Listar apenas o remetente se o
                        // hostname for um provedor de e-mail.
                        tokenSet.add(sender);
                        origem = sender;
                    } else {
                        // Não é um provedor então
                        // o MX deve ser listado.
                        tokenSet.add(mx);
                        origem = mx;
                    }
                    fluxo = origem + ">" + recipient;
                } else if (hostname == null) {
                    origem = (sender == null ? "" : sender + '>') + ip;
                    fluxo = origem + ">" + recipient;
                    hostname = helo;
                } else {
                    String dominio = Domain.extractDomain(hostname, true);
                    origem = (sender == null ? "" : sender + '>') + (dominio == null ? hostname : dominio.substring(1));
                    fluxo = origem + ">" + recipient;
                }
                if (White.contains(client, ip, sender, hostname, result, recipient)) {
//                    if (result.equals("PASS") && White.containsExact(client + ':' + origem + ";PASS>" + recipient)) {
//                        // Limpa da lista BLOCK um possível falso positivo.
//                        Block.clear(null, ip, sender, hostname, result, null);
//                    } else
                    if (White.contains(client, ip, sender, hostname, result, null)) {
                        // Limpa da lista BLOCK um possível falso positivo.
                        Block.clear(null, user, ip, sender, hostname, result, null);
                    }
                    // Calcula frequencia de consultas.
                    String url = Core.getSpamURL(recipient);
                    String ticket = SPF.addQuery(tokenSet);
                    return "action=PREPEND "
                            + "Received-SPFBL: " + result + " "
                            + (url == null ? ticket : url + URLEncoder.encode(ticket, "UTF-8")) + "\n\n";
                } else if (Block.contains(client, ip, sender, hostname, result, recipient)) {
                    // Calcula frequencia de consultas.
                    String ticket = SPF.addQuery(tokenSet);
                    CacheComplain.addComplain(origin, ticket);
                    String url = Core.getUnblockURL(client, ip, sender, hostname, result, recipient);
                    if (url == null) {
                        return "action=554 5.7.1 SPFBL "
                            + "you are permanently blocked in this server.\n\n";
                    } else {
                        return "action=554 5.7.1 SPFBL "
                            + "BLOCKED " + url + "\n\n";
                    }
                } else if (spf != null && spf.isDefinitelyInexistent()) {
                    // O domínio foi dado como inexistente inúmeras vezes.
                    // Rejeitar e denunciar o host pois há abuso de tentativas.
                    String ticket = SPF.addQuery(tokenSet);
                    CacheComplain.addComplain(origin, ticket);
                    return "action=554 5.7.1 SPFBL "
                            + "sender has non-existent internet domain.\n\n";
                } else if (spf != null && spf.isInexistent()) {
                    return "action=554 5.7.1 SPFBL "
                            + "sender has non-existent internet domain.\n\n";
                } else if (result.equals("FAIL")) {
                    String ticket = SPF.addQuery(tokenSet);
                    CacheComplain.addComplain(origin, ticket);
                    // Retornar REJECT somente se não houver 
                    // liberação literal do remetente com FAIL.
                    return "action=554 5.7.1 SPFBL "
                            + sender + " is not allowed to "
                            + "send mail from " + ip + ".\n\n";
                } else if (sender != null && !Domain.isEmail(sender)) {
                    String ticket = SPF.addQuery(tokenSet);
                    CacheComplain.addComplain(origin, ticket);
                    return "action=554 5.7.1 SPFBL "
                            + sender + " is not a valid e-mail address.\n\n";
                } else if (sender != null && Domain.isReserved(sender)) {
                    String ticket = SPF.addQuery(tokenSet);
                    CacheComplain.addComplain(origin, ticket);
                    return "action=554 5.7.1 SPFBL "
                            + sender + " has a reserved domain.\n\n";
                } else if (sender == null && !CacheHELO.match(ip, hostname, false)) {
                    String ticket = SPF.addQuery(tokenSet);
                    CacheComplain.addComplain(origin, ticket);
                    return "action=554 5.7.1 SPFBL invalid hostname.\n\n";
                } else if (hostname == null && Core.isReverseRequired()) {
                    String ticket = SPF.addQuery(tokenSet);
                    CacheComplain.addComplain(origin, ticket);
                    if (Block.tryAdd(ip)) {
                        Server.logDebug("new BLOCK '" + ip + "' added by 'ReverseRequired'.");
                    }
                    return "action=554 5.7.1 SPFBL "
                            + ip + " has no reverse.\n\n";
                } else if (Trap.contains(client, recipient)) {
                    // Calcula frequencia de consultas.
                    String ticket = SPF.addQuery(tokenSet);
                    CacheComplain.addComplain(origin, ticket);
                    return "action=DISCARD SPFBL discarded by spamtrap.\n\n";
                } else if (Defer.count(fluxo) > Core.getFloodMaxRetry()) {
                    // A origem atingiu o limite de atraso 
                    // para liberação do destinatário.
                    long time = System.currentTimeMillis();
                    Defer.end(fluxo);
                    Server.logDefer(time, fluxo, "DEFER FLOOD");
                    String ticket = SPF.addQuery(tokenSet);
                    CacheComplain.addComplain(origin, ticket);
                    return "action=554 5.7.1 SPFBL "
                            + "you are temporarily blocked in this server.\n\n";
                } else if (SPF.isBlocked(tokenSet)) {
                    // Calcula frequencia de consultas.
                    String ticket = SPF.addQuery(tokenSet);
                    CacheComplain.addComplain(origin, ticket);
                    return "action=554 5.7.1 SPFBL "
                            + "you are temporarily blocked in this server.\n\n";
                } else if (!result.equals("PASS") && !CacheHELO.match(ip, hostname, false)) {
                    String ticket = SPF.addQuery(tokenSet);
                    CacheComplain.addComplain(origin, ticket);
                    return "action=554 5.7.1 SPFBL invalid hostname.\n\n";
                } else if (SPF.isBlacklisted(tokenSet)
                        && Defer.defer(fluxo, Core.getDeferTimeBLACK())
                        ) {
                    // Pelo menos um identificador está listado e com atrazo programado de um dia.
                    String url = Core.getReleaseURL(fluxo);
                    if (url == null || Defer.count(fluxo) > 1) {
                        return "action=451 4.7.2 SPFBL "
                                + "you are temporarily blocked on this server.\n\n";
                    } else if (result.equals("PASS") && enviarLiberacao(url, sender, recipient)) {
                        // Envio da liberação por e-mail se 
                        // houver validação do remetente por PASS.
                        return "action=451 4.7.2 SPFBL "
                                + "you are temporarily blocked on this server.\n\n";
                    } else {
                        return "action=451 4.7.2 SPFBL LISTED " + url + "\n\n";
                    }
                } else if (SPF.isGreylisted(tokenSet)
                        && Defer.defer(fluxo, Core.getDeferTimeGRAY())
                        ) {
                    // Pelo menos um identificador está em greylisting com atrazo programado de 10min.
                    return "action=451 4.7.1 SPFBL "
                            + "you are greylisted on this server.\n\n";
                } else if (SPF.isFlood(tokenSet)
                        && !Provider.containsHELO(ip, hostname)
                        && Defer.defer(origem, Core.getDeferTimeFLOOD())
                        ) {
                    // Pelo menos um identificador está com frequência superior ao permitido.
                    Server.logTrace("FLOOD " + tokenSet);
                    return "action=451 4.7.1 SPFBL "
                            + "you are greylisted on this server.\n\n";
                } else if (result.equals("SOFTFAIL")
                        && !Provider.containsHELO(ip, hostname)
                        && Defer.defer(fluxo, Core.getDeferTimeSOFTFAIL())
                        ) {
                    // SOFTFAIL com atrazo programado de 1min.
                    return "action=451 4.7.1 SPFBL "
                            + "you are greylisted on this server.\n\n";
                } else {
                    // Calcula frequencia de consultas.
                    String url = Core.getSpamURL(recipient);
                    String ticket = SPF.addQuery(tokenSet);
                    return "action=PREPEND "
                            + "Received-SPFBL: " + result + " "
                            + (url == null ? ticket : url + URLEncoder.encode(ticket, "UTF-8")) + "\n\n";
                }
            } catch (ProcessException ex) {
                if (ex.getMessage().equals("ERROR: SPF PARSE")) {
                    return "action=REJECT [SPF] "
                            + "One or more SPF records from " + sender + " "
                            + "could not be interpreted. "
                            + "Please see http://www.openspf.org/SPF_"
                            + "Record_Syntax for details.\n\n";
                } else if (ex.getMessage().equals("ERROR: RESERVED")) {
                    return "action=REJECT [SPF] "
                            + "The domain of "
                            + sender + " is a reserved TLD.\n\n";
                } else {
                    return "action=DEFER [SPF] "
                            + "A transient error occurred when "
                            + "checking SPF record from " + sender + ", "
                            + "preventing a result from being reached. "
                            + "Try again later.\n\n";
                }
            } catch (Exception ex) {
                throw new ProcessException("FATAL", ex);
            }
        }
    }

    /**
     * Processa a consulta e retorna o resultado.
     *
     * @param query a expressão da consulta.
     * @return o resultado do processamento.
     */
    protected static String processSPF(
            InetAddress ipAddress,
            Client client,
            User user,
            String query
    ) {
        try {
            String result = "";
            if (query.length() == 0) {
                return "ERROR: QUERY\n";
            } else {
                String origin;
                if (client == null) {
                    origin = ipAddress.getHostAddress();
                } else if (client.hasEmail()) {
                    origin = ipAddress.getHostAddress() + " " + client.getDomain() + " " + client.getEmail();
                } else {
                    origin = ipAddress.getHostAddress() + " " + client.getDomain();
                }
                StringTokenizer tokenizer = new StringTokenizer(query, " ");
                String firstToken = tokenizer.nextToken();
                if (firstToken.equals("SPAM") && tokenizer.countTokens() == 1) {
                    String ticket = tokenizer.nextToken();
                    TreeSet<String> tokenSet = CacheComplain.addComplain(origin, ticket);
                    if (tokenSet == null) {
                        result = "DUPLICATE COMPLAIN\n";
                    } else {
                        String recipient = SPF.getRecipient(ticket);
                        result = "OK " + tokenSet + (recipient == null ? "" : " >" + recipient) + "\n";
                    }
                } else if (firstToken.equals("HAM") && tokenizer.countTokens() == 1) {
                    String ticket = tokenizer.nextToken();
                    TreeSet<String> tokenSet = CacheComplain.deleteComplain(origin, ticket);
                    if (tokenSet == null) {
                        result = "ALREADY REMOVED\n";
                    } else {
                        String recipient = SPF.getRecipient(ticket);
                        result = "OK " + tokenSet + (recipient == null ? "" : " >" + recipient) + "\n";
                    }
                } else if (firstToken.equals("REFRESH") && tokenizer.countTokens() == 1) {
                    String address = tokenizer.nextToken();
                    try {
                        if (CacheSPF.refresh(address, true)) {
                            result = "UPDATED\n";
                        } else {
                            result = "NOT LOADED\n";
                        }
                    } catch (ProcessException ex) {
                        result = ex.getMessage() + "\n";
                    }
                } else if ((firstToken.equals("SPF") && tokenizer.countTokens() >= 4)
                        || tokenizer.countTokens() == 2 || tokenizer.countTokens() == 1
                        || (firstToken.equals("CHECK") && tokenizer.countTokens() == 4)
                        || (firstToken.equals("CHECK") && tokenizer.countTokens() == 3)
                        || (firstToken.equals("CHECK") && tokenizer.countTokens() == 2)) {
                    try {
                        String ip;
                        String sender;
                        String helo;
                        String recipient;
                        String origem;
                        String fluxo;
                        if (firstToken.equals("SPF")) {
                            // Nova formatação de consulta.
                            ip = tokenizer.nextToken();
                            sender = tokenizer.nextToken();
                            while (!sender.endsWith("'") && tokenizer.hasMoreTokens()) {
                                sender += " " + tokenizer.nextToken();
                            }
                            helo = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : "''";
                            recipient = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : "''";
                            ip = ip.substring(1, ip.length() - 1);
                            sender = sender.substring(1, sender.length() - 1);
                            helo = helo.substring(1, helo.length() - 1);
                            recipient = recipient.substring(1, recipient.length() - 1);
                            if (sender.length() == 0) {
                                sender = null;
                            } else {
                                sender = sender.toLowerCase();
                            }
                            if (!Domain.isEmail(recipient)) {
                                recipient = null;
                            }
                        } else if (firstToken.equals("CHECK") && tokenizer.countTokens() == 4) {
                            ip = tokenizer.nextToken().toLowerCase();
                            sender = tokenizer.nextToken().toLowerCase();
                            helo = tokenizer.nextToken().toLowerCase();
                            recipient = tokenizer.nextToken().toLowerCase();
                            if (ip.startsWith("'") && ip.endsWith("'")) {
                                ip = ip.substring(1, ip.length() - 1);
                            }
                            if (sender.startsWith("'") && sender.endsWith("'")) {
                                sender = sender.substring(1, sender.length() - 1);
                            }
                            if (helo.startsWith("'") && helo.endsWith("'")) {
                                helo = helo.substring(1, helo.length() - 1);
                            }
                            if (recipient.startsWith("'") && recipient.endsWith("'")) {
                                recipient = recipient.substring(1, recipient.length() - 1);
                            }
                            if (ip.length() == 0) {
                                ip = null;
                            }
                            if (sender.length() == 0) {
                                sender = null;
                            }
                            if (helo.length() == 0) {
                                helo = null;
                            }
                            if (recipient.length() == 0) {
                                recipient = null;
                            }
                        } else {
                            // Manter compatibilidade da versão antiga.
                            // Versão obsoleta.
                            if (firstToken.equals("CHECK")) {
                                ip = tokenizer.nextToken();
                            } else {
                                ip = firstToken;
                            }
                            if (tokenizer.countTokens() == 2) {
                                sender = tokenizer.nextToken().toLowerCase();
                                helo = tokenizer.nextToken().toLowerCase();
                            } else {
                                sender = null;
                                helo = tokenizer.nextToken().toLowerCase();
                            }
                            recipient = null;
                            if (ip.startsWith("'") && ip.endsWith("'")) {
                                ip = ip.substring(1, ip.length() - 1);
                            }
                            if (sender != null && sender.startsWith("'") && sender.endsWith("'")) {
                                sender = sender.substring(1, sender.length() - 1);
                                if (sender.length() == 0) {
                                    sender = null;
                                }
                            }
                            if (helo.startsWith("'") && helo.endsWith("'")) {
                                helo = helo.substring(1, helo.length() - 1);
                            }
                        }
                        if (!Subnet.isValidIP(ip)) {
                            return "INVALID\n";
                        } else if (
                                Subnet.containsIP("10.0.0.0/8", ip) ||
                                Subnet.containsIP("172.16.0.0/12", ip) ||
                                Subnet.containsIP("192.168.0.0/16", ip) ||
                                Subnet.containsIP("169.254.0.0/16", ip) ||
                                (client != null && client.contains(ip))
                                ) {
                            // Message from LAN.
                            return "LAN\n";
                        } else {
                            TreeSet<String> tokenSet = new TreeSet<String>();
                            ip = Subnet.normalizeIP(ip);
                            tokenSet.add(ip);
                            if (client != null && client.hasEmail()) {
                                // Se houver um cliente válido,
                                // Adicionar no ticket para controle.
                                tokenSet.add(client.getEmail() + ':');
                            }
                            if (Domain.isEmail(recipient)) {
                                // Se houver um remetente válido,
                                // Adicionar no ticket para controle.
                                tokenSet.add('>' + recipient);
                            } else {
                                recipient = null;
                            }
                            
                            // Passar a acompanhar todos os 
                            // HELO quando apontados para o IP para 
                            // uma nova forma de interpretar dados.
                            String hostname;
                            if (CacheHELO.match(ip, helo, false)) {
                                helo = Domain.normalizeHostname(helo, true);
                                hostname = helo;
                            } else {
                                hostname = Reverse.getHostname(ip);
                                hostname = Domain.normalizeHostname(hostname, true);
                            }
                            if (hostname == null) {
                                Server.logDebug("no reverse for " + ip + ".");
                            } else {
                                tokenSet.add(hostname);
                                String ipv4 = CacheHELO.getUniqueIPv4(hostname);
                                if (ipv4 != null) {
                                    // Equivalência de pilha dupla se 
                                    // IPv4 for único para o hostname.
                                    tokenSet.add(ipv4);
                                }
                                String ipv6 = CacheHELO.getUniqueIPv6(hostname);
                                if (ipv6 != null) {
                                    // Equivalência de pilha dupla se 
                                    // IPv6 for único para o hostname.
                                    tokenSet.add(ipv6);
                                }
                            }
                            LinkedList<String> logList = null;
                            if (sender != null && firstToken.equals("CHECK")) {
                                int index = sender.lastIndexOf('@');
                                String domain = sender.substring(index + 1);
                                logList = new LinkedList<String>();
                                try {
                                    CacheSPF.refresh(domain, false);
                                } catch (ProcessException ex) {
                                    logList.add("Cannot refresh SPF registry: " + ex.getErrorMessage());
                                    logList.add("Using cached SPF registry.");
                                }
                            }
                            SPF spf;
                            if (sender == null) {
                                spf = null;
                                result = "NONE";
                            } else if (!Domain.isEmail(sender)) {
                                spf = null;
                                result = "NONE";
                            } else if (Domain.isReserved(sender)) {
                                spf = null;
                                result = "NONE";
                            } else if ((spf = CacheSPF.get(sender)) == null) {
                                result = "NONE";
                            } else if (spf.isInexistent()) {
                                result = "NONE";
                            } else {
                                result = spf.getResult(ip, sender, helo, logList);
                            }
                            if (result.equals("PASS") || (sender != null && Provider.containsHELO(ip, hostname))) {
                                // Quando fo PASS, significa que o domínio
                                // autorizou envio pelo IP, portanto o dono dele
                                // é responsavel pelas mensagens.
                                String mx = Domain.extractHost(sender, true);
                                if (Provider.containsExact(mx)) {
                                    // Listar apenas o remetente se o
                                    // hostname for um provedor de e-mail.
                                    tokenSet.add(sender);
                                    origem = sender;
                                } else {
                                    // Não é um provedor então
                                    // o MX deve ser listado.
                                    tokenSet.add(mx);
                                    origem = mx;
                                }
                                fluxo = origem + ">" + recipient;
                            } else if (hostname == null) {
                                origem = (sender == null ? "" : sender + '>') + ip;
                                fluxo = origem + ">" + recipient;
                                hostname = helo;
                            } else {
                                String dominio = Domain.extractDomain(hostname, true);
                                origem = (sender == null ? "" : sender + '>') + (dominio == null ? hostname : dominio.substring(1));
                                fluxo = origem + ">" + recipient;
                            }
                            if (firstToken.equals("CHECK")) {
                                String results = "\nSPF resolution results:\n";
                                if (logList == null || logList.isEmpty()) {
                                    results += "   NONE\n";
                                } else {
                                    for (String log : logList) {
                                        results += "   " + log + "\n";
                                    }
                                }
                                String white = White.find(client, ip, sender, hostname, result, recipient);
                                if (white != null) {
                                    results += "\nFirst WHITE match: " + white + "\n";
                                }
                                String block = Block.find(client, ip, sender, hostname, result, recipient);
                                if (block != null) {
                                    results += "\nFirst BLOCK match: " + block + "\n";
                                }
                                results += "\n";
                                results += "Considered identifiers and status:\n";
                                tokenSet = expandTokenSet(tokenSet);
                                TreeMap<String,Distribution> distributionMap = CacheDistribution.getMap(tokenSet);
                                for (String token : tokenSet) {
                                    if (!token.startsWith(">") && !token.endsWith(":")) {
                                        float probability;
                                        Status status;
                                        if (distributionMap.containsKey(token)) {
                                            Distribution distribution = distributionMap.get(token);
                                            probability = distribution.getSpamProbability(token);
                                            status = distribution.getStatus(token);
                                        } else {
                                            probability = 0.0f;
                                            status = SPF.Status.WHITE;
                                        }
                                        results += "   " + token
                                                + " " + status.name() + " "
                                                + Core.DECIMAL_FORMAT.format(probability) + "\n";
                                    }
                                }
                                results += "\n";
                                return results;
                            } else if (White.contains(client, ip, sender, hostname, result, recipient)) {
//                                if (result.equals("PASS") && White.containsExact(client + ':' + origem + ";PASS>" + recipient)) {
//                                    // Limpa da lista BLOCK um possível falso positivo.
//                                    Block.clear(null, ip, sender, hostname, result, null);
//                                } else
                                if (White.contains(client, ip, sender, hostname, result, null)) {
//                                  // Limpa da lista BLOCK um possível falso positivo.
                                    Block.clear(null, user, ip, sender, hostname, result, null);
                                }
                                // Calcula frequencia de consultas.
                                String url = Core.getSpamURL(recipient);
                                String ticket = SPF.addQuery(tokenSet);
                                return result + " " + (url == null ? ticket : url + URLEncoder.encode(ticket, "UTF-8")) + "\n";
                            } else if (Block.contains(client, ip, sender, hostname, result, recipient)) {
                                // Calcula frequencia de consultas.
                                String ticket = SPF.addQuery(tokenSet);
                                CacheComplain.addComplain(origin, ticket);
                                String url = Core.getUnblockURL(client, ip, sender, hostname, result, recipient);
                                if (url == null) {
                                    return "BLOCKED\n";
                                } else {
                                    return "BLOCKED " + url + "\n";
                                }
                            } else if (spf != null && spf.isDefinitelyInexistent()) {
                                // O domínio foi dado como inexistente inúmeras vezes.
                                // Rejeitar e denunciar o host pois há abuso de tentativas.
                                String ticket = SPF.addQuery(tokenSet);
                                CacheComplain.addComplain(origin, ticket);
                                return "NXDOMAIN\n";
                            } else if (spf != null && spf.isInexistent()) {
                                return "NXDOMAIN\n";
                            } else if (result.equals("FAIL")) {
                                String ticket = SPF.addQuery(tokenSet);
                                CacheComplain.addComplain(origin, ticket);
                                // Retornar FAIL somente se não houver 
                                // liberação literal do remetente com FAIL.
                                return "FAIL\n";
                            } else if (sender != null && !Domain.isEmail(sender)) {
                                String ticket = SPF.addQuery(tokenSet);
                                CacheComplain.addComplain(origin, ticket);
                                return "INVALID\n";
                            } else if (sender != null && Domain.isReserved(sender)) {
                                String ticket = SPF.addQuery(tokenSet);
                                CacheComplain.addComplain(origin, ticket);
                                return "INVALID\n";
                            } else if (sender == null && !CacheHELO.match(ip, hostname, false)) {
                                String ticket = SPF.addQuery(tokenSet);
                                CacheComplain.addComplain(origin, ticket);
                                // HELO inválido sem remetente.
                                return "INVALID\n";
                            } else if (hostname == null && Core.isReverseRequired()) {
                                String ticket = SPF.addQuery(tokenSet);
                                CacheComplain.addComplain(origin, ticket);
                                if (Block.tryAdd(ip)) {
                                    Server.logDebug("new BLOCK '" + ip + "' added by 'ReverseRequired'.");
                                }
                                // Require a valid HELO or reverse.
                                return "INVALID\n";
                            } else if (Trap.contains(client, recipient)) {
                                // Calcula frequencia de consultas.
                                String ticket = SPF.addQuery(tokenSet);
                                CacheComplain.addComplain(origin, ticket);
                                return "SPAMTRAP\n";
                            } else if (Defer.count(fluxo) > Core.getFloodMaxRetry()) {
                                // A origem atingiu o limite de atraso 
                                // para liberação do destinatário.
                                long time = System.currentTimeMillis();
                                Defer.end(fluxo);
                                Server.logDefer(time, fluxo, "DEFER FLOOD");
                                String ticket = SPF.addQuery(tokenSet);
                                CacheComplain.addComplain(origin, ticket);
                                return "BLOCKED\n";
                            } else if (SPF.isBlocked(tokenSet)) {
                                 // Calcula frequencia de consultas.
                                String ticket = SPF.addQuery(tokenSet);
                                CacheComplain.addComplain(origin, ticket);
                                return "BLOCKED\n";
                            } else if (!result.equals("PASS") && !CacheHELO.match(ip, hostname, false)) {
                                String ticket = SPF.addQuery(tokenSet);
                                CacheComplain.addComplain(origin, ticket);
                                return "INVALID\n";
                            } else if (SPF.isBlacklisted(tokenSet)
                                    && Defer.defer(fluxo, Core.getDeferTimeBLACK())
                                    ) {
                                // Pelo menos um identificador do conjunto está em lista negra com atrazo de 1 dia.
                                String url = Core.getReleaseURL(fluxo);
                                if (url == null || Defer.count(fluxo) > 1) {
                                    return "LISTED\n";
                                } else if (result.equals("PASS") && enviarLiberacao(url, sender, recipient)) {
                                    // Envio da liberação por e-mail se 
                                    // houver validação do remetente por PASS.
                                    return "LISTED\n";
                                } else {
                                    return "LISTED " + url + "\n";
                                }
                            } else if (SPF.isGreylisted(tokenSet)
                                    && Defer.defer(fluxo, Core.getDeferTimeGRAY())
                                    ) {
                                // Pelo menos um identificador do conjunto está em greylisting com atrazo de 10min.
                                return "GREYLIST\n";
                            } else if (SPF.isFlood(tokenSet)
                                    && !Provider.containsHELO(ip, hostname)
                                    && Defer.defer(origem, Core.getDeferTimeFLOOD())
                                    ) {
                                // Pelo menos um identificador está com frequência superior ao permitido.
                                Server.logTrace("FLOOD " + tokenSet);
                                return "GREYLIST\n";
                            } else if (result.equals("SOFTFAIL")
                                    && !Provider.containsHELO(ip, hostname)
                                    && Defer.defer(fluxo, Core.getDeferTimeSOFTFAIL())
                                    ) {
                                // SOFTFAIL com atrazo de 1min.
                                return "GREYLIST\n";
                            } else {
                                // Calcula frequencia de consultas.
                                String url = Core.getSpamURL(recipient);
                                String ticket = SPF.addQuery(tokenSet);
                                return result + " " + (url == null ? ticket : url + URLEncoder.encode(ticket, "UTF-8")) + "\n";
                            }
                        }
                    } catch (ProcessException ex) {
                        if (ex.getMessage().equals("ERROR: HOST NOT FOUND")) {
                            return "NXDOMAIN\n";
                        } else {
                            throw ex;
                        }
                    }
                } else {
                    return "ERROR: QUERY\n";
                }
            }
            return result;
        } catch (ProcessException ex) {
            Server.logError(ex.getCause());
            return ex.getMessage() + "\n";
        } catch (Exception ex) {
            Server.logError(ex);
            return "ERROR: FATAL\n";
        }
    }
    
    private static boolean enviarLiberacao(
            String url,
            String remetente,
            String destinatario
            ) {
        if (
                Core.hasSMTP()
                && Core.hasAdminEmail()
                && Domain.isEmail(remetente)
                && Domain.isEmail(destinatario)
                && url != null
                && !NoReply.contains(remetente)
                ) {
            try {
                Server.logDebug("sending liberation by e-mail.");
                InternetAddress[] recipients = InternetAddress.parse(remetente);
                Properties props = System.getProperties();
                Session session = Session.getDefaultInstance(props);
                MimeMessage message = new MimeMessage(session);
                message.setHeader("Date", Core.getEmailDate());
                message.setFrom(Core.getAdminEmail());
                message.addRecipients(Message.RecipientType.TO, recipients);
                message.setSubject("Liberação de recebimento");
                // Corpo da mensagem.
                StringBuilder builder = new StringBuilder();
                builder.append("<html>\n");
                builder.append("  <head>\n");
                builder.append("    <meta charset=\"UTF-8\">\n");
                builder.append("    <title>Liberação de recebimento</title>\n");
                builder.append("  </head>\n");
                builder.append("  <body>\n");
                builder.append("       O recebimento da sua mensagem para ");
                builder.append(destinatario);
                builder.append(" está sendo atrasado por suspeita de SPAM.<br>\n");
                builder.append("       Para que sua mensagem seja liberada, ");
                builder.append("acesse este link e resolva o desafio reCAPTCHA:<br>\n");
                builder.append("       <a href=\"");
                builder.append(url);
                builder.append("\">");
                builder.append(url);
                builder.append("</a><br>\n");
                builder.append("  </body>\n");
                builder.append("</html>\n");
                message.setContent(builder.toString(), "text/html;charset=UTF-8");
                message.saveChanges();
                // Enviar mensagem.
                return Core.offerMessage(message);
            } catch (Exception ex) {
                Server.logError(ex);
                return false;
            }
        } else {
            return false;
        }
    }

    public static String createTicket(TreeSet<String> tokenSet) throws ProcessException {
        String ticket = Server.getNewTicketDate();
        for (String token : tokenSet) {
            ticket += " " + token;
        }
        return Server.encrypt(ticket);
    }

    private static Date getTicketDate(String date) throws ProcessException {
        try {
            return Server.parseTicketDate(date);
        } catch (ParseException ex) {
            throw new ProcessException("ERROR: INVALID TICKET", ex);
        }
    }
    
    public static TreeSet<String> expandTokenSet(
            TreeSet<String> tokenSet) {
        TreeSet<String> expandedSet = new TreeSet<String>();
        for (String token : tokenSet) {
            if (token != null) {
                expandedSet.add(token);
                boolean expand;
                if (token.startsWith("@") && Domain.isHostname(token.substring(1))) {
                    token = '.' + token.substring(1);
                    expand = true;
                } else if (token.startsWith(".") && Domain.isHostname(token.substring(1))) {
                    expand = true;
                } else if (!token.startsWith(".") && Domain.isHostname(token)) {
                    token = '.' + token;
                    expand = true;
                } else {
                    expand = false;
                }
                if (expand) {
                    try {
                        String dominio = Domain.extractDomain(token, true);
//                        String subdominio = Domain.extractHost(token, true);
//                        if (subdominio != null) {
//                            while (!subdominio.equals(dominio)) {
//                                expandedSet.add(subdominio);
//                                int index = subdominio.indexOf('.', 1);
//                                subdominio = subdominio.substring(index);
//                            }
//                        }
                        expandedSet.add(dominio);
                    } catch (ProcessException ex) {
                        Server.logError(ex);
                    }
                }
            }
        }
        return expandedSet;
    }

    public static String addQuery(
            TreeSet<String> tokenSet
            ) throws ProcessException {
//        long time = System.currentTimeMillis();
        for (String token : expandTokenSet(tokenSet)) {
            if (CacheComplain.isValid(token)) {
                Distribution distribution = CacheDistribution.get(token, true);
                if (distribution != null) {
                    distribution.addQuery();
                }
            }
        }
        String ticket = SPF.createTicket(tokenSet);
//        Server.logTicket(time, tokenSet, ticket);
        return ticket;
    }

    public static long getComplainTTL(String token) {
        Distribution distribution = CacheDistribution.get(token, false);
        if (distribution == null) {
            // Distribuição não encontrada.
            // Considerar que não está listado.
            return 0;
        } else {
            // Transformar em segundos.
            return distribution.getComplainTTL() / 1000;
        }
    }
    
    public static void createDistribution(String token) {
        CacheDistribution.get(token, true);
    }

    public static boolean isBlacklisted(String token) {
        if (CacheComplain.isValid(token)) {
            Distribution distribution = CacheDistribution.get(token, false);
            if (distribution == null) {
                return false;
            } else {
                return distribution.isBlacklisted(token);
            }
        } else {
            return false;
        }
    }

    public static boolean isGreylisted(String token) {
        if (CacheComplain.isValid(token)) {
            Distribution distribution = CacheDistribution.get(token, false);
            if (distribution == null) {
                // Distribuição não encontrada.
                // Considerar que não está listado.
                return false;
            } else {
                return distribution.isGreylisted(token);
            }
        } else {
            return false;
        }
    }

    public static boolean isBlocked(String token) {
        if (CacheComplain.isValid(token)) {
            Distribution distribution = CacheDistribution.get(token, true);
            return distribution.isBlocked(token);
        } else {
            return false;
        }
    }
    
    public static boolean isFlood(String token) {
        if (Ignore.contains(token)) {
            return false;
        } else if (CacheComplain.isValid(token)) {
            Distribution distribution = CacheDistribution.get(token, false);
            if (distribution == null) {
                // Distribuição não encontrada.
                // Considerar que não é rajada.
                return false;
            } else {
                return distribution.isFlood(token);
            }
        } else {
            return false;
        }
    }

    private static boolean isGreylisted
            (TreeSet<String> tokenSet
            ) throws ProcessException {
        boolean greylisted = false;
        for (String token : expandTokenSet(tokenSet)) {
            if (isGreylisted(token)) {
                greylisted = true;
            }
        }
        return greylisted;
    }

    private static boolean isBlacklisted(
            TreeSet<String> tokenSet
            ) throws ProcessException {
        boolean blacklisted = false;
        for (String token : expandTokenSet(tokenSet)) {
            if (isBlacklisted(token)) {
                blacklisted = true;
            }
        }
        return blacklisted;
    }

    private static boolean isBlocked(
            TreeSet<String> tokenSet
            ) throws ProcessException {
        boolean blocked = false;
        for (String token : expandTokenSet(tokenSet)) {
            if (isBlocked(token)) {
                blocked = true;
            }
        }
        return blocked;
    }
    
    private static boolean isFlood(
            TreeSet<String> tokenSet
            ) throws ProcessException {
        for (String token : expandTokenSet(tokenSet)) {
            if (isFlood(token) && !Ignore.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Enumeração do status da distribuição.
     */
    public enum Status implements Serializable {

        WHITE, // Whitelisted
        GRAY, // Graylisted
        BLACK, // Blacklisted
        BLOCK, // Blocked
        IGNORE, // Ignored
        CLOSED; // Closed
    }
    /**
     * Constantes que determinam os limiares de listagem.
     */
    private static final float LIMIAR1 = 0.25f;
    private static final float LIMIAR2 = 0.50f;
    private static final float LIMIAR3 = 0.75f;

    /**
     * Classe que representa a distribuição binomial entre SPAM e HAM.
     */
    public static final class Distribution implements Serializable {

        private static final long serialVersionUID = 1L;
        private int complain; // Quantidade total de reclamações.
        private long lastQuery; // Última consulta à distribuição.
        private long lastComplain; // Última denúncia à distribuição.
        private Status status; // Status atual da distribuição.
        private NormalDistribution frequency = null; // Frequência média em segundos.
        
        public Distribution() {
            reset();
        }
        
        public void reset() {
            complain = 0;
            lastQuery = 0;
            lastComplain = 0;
            status = Status.WHITE;
            frequency = null;
            CacheDistribution.CHANGED = true;
        }
        
//        private void add(Distribution other) {
//            this.complain += other.complain;
//            this.lastQuery = Math.max(this.lastQuery, other.lastQuery);
//            this.lastComplain = Math.max(this.lastComplain, other.lastComplain);
//            if (this.frequency == null) {
//                this.frequency = other.frequency;
//            } else {
//                this.frequency.add(other.frequency);
//            }
//        }

        public boolean isExpired7() {
            return System.currentTimeMillis() - lastQuery > 604800000;
        }

        public boolean isExpired14() {
            return System.currentTimeMillis() - lastQuery > 604800000 * 2;
        }

        public long getComplainTTL() {
            long ttl = lastComplain + 604800000 - System.currentTimeMillis();
            if (ttl < 0) {
                return 0;
            } else {
                return ttl;
            }
        }

        public boolean clear() {
            if (complain == 0) {
                return false;
            } else {
                complain = 0;
                status = Status.WHITE;
                CacheDistribution.CHANGED = true;
                return true;
            }
        }

        public boolean hasFrequency() {
            return frequency != null;
        }
        
        public Double getFrequencyMin() {
            if (frequency == null) {
                return null;
            } else {
                return frequency.getMinimum();
            }
        }

        public boolean hasLastQuery() {
            return lastQuery > 0;
        }
        
        public long getIdleTimeMillis() {
            if (lastQuery == 0) {
                return 0;
            } else {
                return System.currentTimeMillis() - lastQuery;
            }
        }

        public String getFrequencyLiteral() {
            if (hasFrequency()) {
                int frequencyInt = frequency.getMaximumInt();
                long idleTimeInt = getIdleTimeMillis();
                if (idleTimeInt > frequencyInt * 5 && idleTimeInt > 3600000) {
                    return "DEAD";
                } else {
                    char sinal = '~';
                    if (idleTimeInt > frequencyInt * 3) {
                        sinal = '>';
                    }
                    if (frequencyInt >= 3600000) {
                        return sinal + ((frequencyInt / 3600000) + "h");
                    } else if (frequencyInt >= 60000) {
                        return sinal + ((frequencyInt / 60000) + "min");
                    } else if (frequencyInt >= 1000) {
                        return sinal + ((frequencyInt / 1000) + "s");
                    } else {
                        return sinal + (frequencyInt + "ms");
                    }
                }
            } else {
                return "NEW";
            }
        }

        private float getInterval(boolean refresh) {
            long currentTime = System.currentTimeMillis();
            float interval;
            if (lastQuery == 0) {
                interval = 0;
            } else {
                interval = (float) (currentTime - lastQuery) / (float) 1000;
            }
            if (refresh) {
                lastQuery = currentTime;
                CacheDistribution.CHANGED = true;
            }
            return interval;
        }

        public void addQuery() {
            float interval = getInterval(true);
            if (interval == 0.0f) {
                // Se não houver intervalo definido,
                // considerar frequência nula.
                frequency = null;
            } else if (frequency == null) {
                frequency = new NormalDistribution(interval);
            } else {
                frequency.addElement(interval);
            }
        }
        
        public float getSpamProbability(String token) {
            int[] binomial = getBinomial();
            if (token != null) {
                for (Peer peer : Peer.getSet()) {
                    Peer.Binomial peerBinomial = peer.getBinomial(token);
                    if (peerBinomial != null) {
                        binomial[0] += peerBinomial.getHAM();
                        binomial[1] += peerBinomial.getSPAM();
                    }
                }
            }
            int total = binomial[0] + binomial[1];
            float probability = (float) binomial[1] / (float) total;
            if (total == 0) {
                return 0.0f;
            } else if (probability > LIMIAR1 && binomial[1] < 3) {
                // Quantidade pequena para dar precisão.
                return LIMIAR1;
            } else if (probability > LIMIAR2 && binomial[1] < 5) {
                // Quantidade pequena para dar precisão.
                // Para haver bloqueio temporário é 
                // necessário pelo meno cinco denuncias.
                return LIMIAR2;
            } else if (probability > LIMIAR3 && binomial[1] < 7) {
                // Quantidade pequena para dar precisão.
                // Para haver bloqueio definitivo é 
                // necessário pelo meno sete denuncias.
                return LIMIAR3;
            } else {
                return probability;
            }
        }
        
        public Status getStatus() {
            return status;
        }
        
        /**
         * Máquina de estados para listar em um pico e retirar a listagem
         * somente quando o total cair consideravelmente após este pico.
         *
         * @return o status atual da distribuição.
         */
        public Status getStatus(String token) {
            Status statusOld = status;
            float probability = getSpamProbability(token);
            if (probability < 0.015625f) {
                status = Status.WHITE;
            } else if (probability < LIMIAR1) {
                status = statusOld == Status.BLACK ? Status.GRAY : statusOld;
            } else if (probability > LIMIAR3) {
                status = (Subnet.isValidIP(token) ? Status.BLACK : Status.BLOCK);
            } else if (probability > LIMIAR2) {
                status = Status.BLACK;
            } else if (probability > LIMIAR1) {
                status = (Subnet.isValidIP(token) ? Status.GRAY : Status.BLACK);
            }
            return status;
        }

        /**
         * Verifica se o estado atual da distribuição é greylisted.
         *
         * @param query se contabiliza uma consulta com a verificação.
         * @return verdadeiro se o estado atual da distribuição é greylisted.
         */
        public boolean isBlacklisted(String token) {
            return getStatus(token) == Status.BLACK;
        }
        
        public boolean isWhitelisted(String token) {
            return getStatus(token) == Status.WHITE;
        }
        
        public boolean isNotWhitelisted(String token) {
            return getStatus(token) != Status.WHITE;
        }

        public boolean isGreylisted(String token) {
            return getStatus(token) == Status.GRAY;
        }

        public boolean isBlocked(String token) {
            return getStatus(token) == Status.BLOCK;
        }
        
        public boolean isFlood(String token) {
            Double frequency = getFrequencyMin();
            if (frequency == null) {
                return false;
            } else if (Subnet.isValidIP(token)) {
                return frequency < Core.getFloodTimeIP();
            } else if (Domain.isEmail(token)) {
                return frequency < Core.getFloodTimeSender();
            } else {
                return frequency < Core.getFloodTimeHELO();
            }
        }

        public synchronized boolean removeSpam() {
            if (complain > 0) {
                complain--;
                CacheDistribution.CHANGED = true;
                return true;
            } else {
                return false;
            }
        }
        
        private synchronized void resetSpamInterval() {
            complain = 0;
        }
        
        private synchronized void addSpamInterval() {
            complain++;
        }

        public synchronized boolean addSpam() {
            if (complain < Integer.MAX_VALUE) {
                complain++;
                lastComplain = System.currentTimeMillis();
                CacheDistribution.CHANGED = true;
                return true;
            } else {
                return false;
            }
        }
        
        public synchronized int[] getBinomial() {
            if (frequency == null) {
                return new int[2];
            } else if (frequency.getMinimum() > 0.0d) {
                int[] result = new int[2];
                double semana = 60 * 60 * 24 * 7;
                int total = (int) (semana / frequency.getMinimum());
                if (total < complain) {
                    total = complain;
                }
                int ham = total - complain;
                int spam = complain;
                result[0] = ham;
                result[1] = spam;
                return result;
            } else {
                return new int[2];
            }
        }
        
        public synchronized int[] getBinomial(String token) {
            int[] result = new int[2];
            if (token != null) {
                for (Peer peer : Peer.getSet()) {
                    Peer.Binomial peerBinomial = peer.getBinomial(token);
                    if (peerBinomial != null) {
                        result[0] += peerBinomial.getHAM();
                        result[1] += peerBinomial.getSPAM();
                    }
                }
            }
            if (frequency != null && frequency.getMinimum() > 0.0d) {
                double semana = 60 * 60 * 24 * 7;
                int total = (int) (semana / frequency.getMinimum());
                if (total < complain) {
                    total = complain;
                }
                int ham = total - complain;
                int spam = complain;
                result[0] += ham;
                result[1] += spam;
            }
            return result;
        }

        @Override
        public String toString() {
            return Float.toString(getSpamProbability(null));
        }
    }
    
    /**
     * Classe que representa a distribuição binomial entre HAM e SPAM.
     */
    public static final class Binomial implements Serializable {

        private static final long serialVersionUID = 1L;
        
        private int ham; // Quantidade total de HAM em sete dias.
        private int spam; // Quantidade total de SPAM em sete dias
        private final Status status;
        private NormalDistribution frequency = null;
        private long lastQuery = 0;
        
        public Binomial(String token, Distribution distribution) {
            int[] binomial = distribution.getBinomial(token);
            this.ham = binomial[0];
            this.spam = binomial[1];
            this.status = distribution.getStatus(token);
            this.frequency = distribution.frequency;
            this.lastQuery = distribution.lastQuery;
       }
        
        public Binomial(Status status) {
            this.status = status;
            this.ham = 0;
            this.spam = 0;
            this.frequency = null;
            this.lastQuery = 0;
        }
        
        public void add(String token, Distribution distribution) {
            int[] binomial = distribution.getBinomial(token);
            this.ham += binomial[0];
            this.spam += binomial[1];
            if (this.frequency == null) {
                this.frequency = distribution.frequency;
            } else {
                this.frequency.add(distribution.frequency);
            }
            if (this.lastQuery < distribution.lastQuery) {
                this.lastQuery = distribution.lastQuery;
            }
        }
        
        public int getSPAM() {
            return spam;
        }
        
        public int getHAM() {
            return ham;
        }
        
        public Status getStatus() {
            return status;
        }
        
        public boolean clear() {
            if (spam > 0) {
                this.ham += spam;
                this.spam = 0;
                return true;
            } else {
                return false;
            }
        }
        
        public synchronized float getSpamProbability() {
            int total = ham + spam;
            float probability = (float) spam / (float) total;
            if (total == 0) {
                return 0.0f;
            } else if (probability > LIMIAR1 && spam < 3) {
                // Quantidade pequena para dar precisão.
                return LIMIAR1;
            } else if (probability > LIMIAR2 && spam < 5) {
                // Quantidade pequena para dar precisão.
                // Para haver bloqueio temporário é 
                // necessário pelo meno cinco denuncias.
                return LIMIAR2;
            } else if (probability > LIMIAR3 && spam < 7) {
                // Quantidade pequena para dar precisão.
                // Para haver bloqueio definitivo é 
                // necessário pelo meno sete denuncias.
                return LIMIAR3;
            } else {
                return probability;
            }
        }
        
        public long getIdleTimeMillis() {
            if (lastQuery == 0) {
                return 0;
            } else {
                return System.currentTimeMillis() - lastQuery;
            }
        }
        
        public String getFrequencyLiteral() {
            if (frequency == null) {
                return "NEW";
            } else {
                int frequencyInt = frequency.getMaximumInt();
                long idleTimeInt = getIdleTimeMillis();
                if (idleTimeInt > frequencyInt * 5 && idleTimeInt > 604800000) {
                    return "DEAD";
                } else {
                    char sinal = '~';
                    if (idleTimeInt > frequencyInt * 3) {
                        sinal = '>';
                    }
                    if (frequencyInt >= 3600000) {
                        return sinal + ((frequencyInt / 3600000) + "h");
                    } else if (frequencyInt >= 60000) {
                        return sinal + ((frequencyInt / 60000) + "min");
                    } else if (frequencyInt >= 1000) {
                        return sinal + ((frequencyInt / 1000) + "s");
                    } else {
                        return sinal + (frequencyInt + "ms");
                    }
                }
            }
        }
        
        @Override
        public String toString() {
            return Float.toString(getSpamProbability());
        }
    }
}
