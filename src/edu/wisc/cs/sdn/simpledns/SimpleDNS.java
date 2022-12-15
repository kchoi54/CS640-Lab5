package edu.wisc.cs.sdn.simpledns;
import edu.wisc.cs.sdn.simpledns.packet.*;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class SimpleDNS 
{
    private static final int SOCK_PORT = 8053;
    private static final int DNS_PORT = 53;
    private static final int BUF_SIZE = 4096;


    private static DatagramSocket socket;

    private static InetAddress RootServer;
    private static Map<String, List<String>> IpMap = new HashMap<String, List<String>>();
    public static void main(String[] args) throws Exception
    {
        System.out.println("Hello, DNS!"); 
        if (args.length != 4) {
            System.err.println("Usage: java edu.wisc.cs.sdn.simpledns.SimpleDNS -r <root server ip> -e <ec2 csv>");
            return;
        }       
        String rootIp = args[1];
        String ec2Path = args[3];
        loadEC2(ec2Path); //load ec2 to IpMap

        List<Short> validDnsType = Arrays.asList(DNS.TYPE_A, DNS.TYPE_AAAA, DNS.TYPE_NS, DNS.TYPE_CNAME); //call !validDnsType.contains() to filter invalid type
        
        RootServer = InetAddress.getByName(rootIp);
        socket = new DatagramSocket(SOCK_PORT);
        byte[] buffer = new byte[BUF_SIZE];
        while (true) {
                DatagramPacket receivePkt = new DatagramPacket(buffer, buffer.length);
                socket.receive(receivePkt);
                DNS receiveDns = DNS.deserialize(receivePkt.getData(), receivePkt.getLength());

                int clientPort = receivePkt.getPort();
                InetAddress clientAddress = receivePkt.getAddress();

                //drop if not standard query 
                if (receiveDns.getOpcode() != DNS.OPCODE_STANDARD_QUERY) 
                { continue; }
            
                //assumption: always receive one question
                DNSQuestion q = receiveDns.getQuestions().get(0);
                //drop if not A, AA, NS, CNAME
                if (!validDnsType.contains(q.getType())) 
                { continue; }
                
                //receiveDns.set
                DNS queryDns = dnsFromQuestion(receiveDns.getId(), true, 
                    receiveDns.isRecursionDesired(), false, q);

                //DNS outputDns = receiveDns
                DNS outputDns = dnsFromQuestion(receiveDns.getId(), false, 
                    false, receiveDns.isRecursionDesired(), q);
                try {
                    if (receiveDns.isRecursionDesired()) 
                    { recursive(RootServer, queryDns, outputDns); }
                    else 
                    { request(queryDns, RootServer); }

                    //forward opt
                    outputDns.addAdditional(receiveDns.getAdditional().get(0));

                    outputDns = applyIpMap(outputDns); //apply ec2 map
                    
                    byte[] outputSerial = outputDns.serialize();
                    DatagramPacket outputPkt = new DatagramPacket(outputSerial, outputSerial.length, clientAddress, clientPort);
                    socket.send(outputPkt);
                } catch (Exception e) {
                    System.err.println(e);
                }
        }
    }

    /**
     * load ec2 as hashmap
     * @return
     */
    private static boolean loadEC2(String ec2Path) {
        try (BufferedReader br = new BufferedReader(new FileReader(ec2Path))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] sub = line.split("/|,");
                List<String> entry = new ArrayList<String>();
                entry.add(sub[1]); //mask
                entry.add(sub[2]);
                IpMap.put(sub[0], entry);    
            }
        } catch (Exception e) {
            System.err.println(e);
            return false;
        }
        return true;
    }

    private static DNS dnsFromQuestion(short id, boolean query, boolean recurDesired, boolean recurAvail, DNSQuestion q) {
        DNS dns = new DNS();
        dns.setId(id);
        dns.addQuestion(q);
        dns.setQuery(query);
        dns.setRecursionDesired(recurDesired);
        dns.setRecursionAvailable(recurAvail);
        return dns;
    }

    /**
     * 
     * @param root addr to request query
     * @param queryDns dns to send
     * @param outputDns dns to store answers, auth, and addt
     * @return true if answer found, otherwise false
     */
    private static boolean recursive(InetAddress root, DNS queryDns, DNS outputDns) {
        System.out.println("-------------------request-------------------");
        System.out.println(root);
        System.out.println(queryDns);

        //request
        DNS responseDns = request(queryDns, root);
        
        System.out.println("-------------------response-------------------");
        System.out.println(root);
        System.out.println(responseDns);
        System.out.println("---------------------------------------------\n");

        if (responseDns.getRcode() != DNS.RCODE_NO_ERROR)
        { return false; }

        List<DNSResourceRecord> answers = responseDns.getAnswers();
        List<DNSResourceRecord> authorities = responseDns.getAuthorities();
        List<DNSResourceRecord> additionals = responseDns.getAdditional();

        if (answers.isEmpty()) {
            outputDns.setAuthorities(authorities);
            outputDns.setAdditional(additionals);
        } else {
            for (DNSResourceRecord ans : answers) { 
                outputDns.addAnswer(ans); 
                
                //handle CNAME
            }
            return true; //found
        }

        // for (DNSResourceRecord ans: ansDNS.getAnswers())
        // {
        //     if (ans.getType() == DNS.TYPE_CNAME) {
        //         boolean isInAnswers = false;
        //         for (DNSResourceRecord ans2 : ansDNS.getAnswers()) {
        //             // check the resolved CNAME is included in answers
        //             String name = ans2.getName();
        //             String data = ((DNSRdataName) ans.getData()).getName();

        //             if (isInAnswers = name.equals(data)) 
        //             { break; }
        //         }
        //         if (isInAnswers) 
        //         { continue; }

        //         /* RESOLVE CNAME HERE*/
        //         DNSQuestion cnameQuery = new DNSQuestion(((DNSRdataName) ans.getData()).getName(), type);
        //         DatagramPacket cnamePkt = recursive(root, type, cnameQuery);
                
        //         if (cnamePkt == null)
        //         { continue; }

        //         DNS cnameDNS = DNS.deserialize(cnamePkt.getData(), cnamePkt.getLength());

        //         answers.addAll(cnameDNS.getAnswers());

        //         authorities = cnameDNS.getAuthorities();
        //         additionals = cnameDNS.getAdditional();
        //     }
        // }
        
        //recurrsive call
        DNSQuestion question = queryDns.getQuestions().get(0);
        boolean found = false;
        for (int authI=0; authI < authorities.size() && !found; authI--)
        {
            DNSResourceRecord auth = authorities.get(authI);
            String name = ((DNSRdataName) auth.getData()).getName();

            System.out.println(auth);
            for (int addtI=0; addtI <= additionals.size() && !found; addtI++)
            {
                InetAddress addr = null;

                if (addtI < additionals.size())
                { 
                    DNSResourceRecord addt = additionals.get(addtI); 
                    
                    if (addt.getType() != question.getType() || !name.equals(addt.getName()))
                    { continue; }

                    addr = ((DNSRdataAddress) addt.getData()).getAddress();
                } else { //addtI == additionals.size() means auth was not found in addt
                    //then query auth nameserver
                    System.out.println("auth not found");
                    DNSQuestion nsQuestion = new DNSQuestion(name, question.getType());
                    
                    DNS authQueryDns = dnsFromQuestion(queryDns.getId(), true, 
                        queryDns.isRecursionDesired(), false, nsQuestion);
                    
                    DNS authOutputDns = dnsFromQuestion(queryDns.getId(), false, 
                        false, queryDns.isRecursionDesired(), nsQuestion);

                    if(recursive(RootServer, authQueryDns, authOutputDns)) { //if name server found
                        DNSRdataAddress data = (DNSRdataAddress) authOutputDns.getAnswers().get(0).getData();
                        addr = data.getAddress();
                    }
                }

                if (addr == null) //if auth ns not found move to next 
                { break; }

                found = recursive(addr, queryDns, outputDns);
            }
        }
        
        return !outputDns.getAnswers().isEmpty(); //return true if answer is not empty
    }

    /**
     * non-recursive request
     * @param dns
     * @param dst
     * @return
     */
    private static DNS request(DNS dns, InetAddress dst) {
        try {
            byte[] dnsSerial = dns.serialize();
            byte[] buffer = new byte[BUF_SIZE];
            DatagramPacket toSend = new DatagramPacket(dnsSerial, dnsSerial.length, dst, DNS_PORT);
            DatagramPacket toReceive = new DatagramPacket(buffer, buffer.length);
            
            socket.send(toSend);
            socket.receive(toReceive);
            
            return DNS.deserialize(toReceive.getData(), toReceive.getLength());
        } catch (Exception e) {
            System.err.println(e);
        }
        return null;
    }
    
    private static DNS applyIpMap (DNS dns) {
        List<DNSResourceRecord> answer = dns.getAnswers();
        List<DNSResourceRecord> newAnswer = new ArrayList<DNSResourceRecord>();
        for (DNSResourceRecord ans : answer) {
            DNSResourceRecord newAns = ans;
            if (ans.getType() == DNS.TYPE_A) {
                String ansIp = ans.getData().toString();
                for (String ip : IpMap.keySet()) {
                    //System.out.println("ip: "+ansIp+" entry: "+ip+"/"+IpMap.get(ip).get(0));
                    if ((stringToInt(ansIp) ^ stringToInt(ip)) < (((long) 1) << (32 - Integer.parseInt(IpMap.get(ip).get(0))))) {
                        DNSRdata dnsRDataString = new DNSRdataString(String.format("%s-%s", IpMap.get(ip).get(1), ansIp));
                        newAns = new DNSResourceRecord(ans.getName(), DNS.TYPE_TXT, dnsRDataString);
                        break;
                    }
                }
            }
            newAnswer.add(newAns); 
        }
        dns.setAnswers(newAnswer);
        return dns;
    }   
    
    private static long stringToInt(String ip) {
        String[] array = ip.split("\\.");
        long addr = 0;
        for (int i=0; i<4; i++) {
            addr |= ( ((long)Integer.parseInt(array[i])) << (24 - i * 8) );
        } 
        return addr;
    }
}   

