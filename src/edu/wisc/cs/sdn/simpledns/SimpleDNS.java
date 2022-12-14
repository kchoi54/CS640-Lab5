package edu.wisc.cs.sdn.simpledns;
import edu.wisc.cs.sdn.simpledns.packet.*;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class SimpleDNS 
{
    public static final int SOCK_PORT = 8053;
    public static final int DNS_PORT = 53;
    public static final int BUF_SIZE = 4096;


    public static DatagramSocket socket;
    public static DatagramPacket dns;

    public static Map<String, List<String>> ipMap = new HashMap<String, List<String>>();
    public static void main(String[] args) throws Exception
    {
        System.out.println("Hello, DNS!"); 
        if (args.length != 4) {
            System.err.println("Usage: java edu.wisc.cs.sdn.simpledns.SimpleDNS -r <root server ip> -e <ec2 csv>");
            return;
        }       
        String rootIp = args[1];
        String file = args[3];

        //load ec2 as hashmap
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String city = line.split(",")[1];
                String [] ip = line.split(",")[0].split("/");
                List<String> entry = new ArrayList<String>();
                entry.add(ip[1]); //mask
                entry.add(city);
                ipMap.put(ip[0], entry);    
            }
        } catch (Exception e) {
            System.err.println(e);
        }


        InetAddress root = InetAddress.getByName(rootIp);
        socket = new DatagramSocket(SOCK_PORT);
        byte[] buffer = new byte[BUF_SIZE];
        while (true) {
                DatagramPacket queryPkt = new DatagramPacket(buffer, buffer.length);
                socket.receive(queryPkt);
                DNS queryDNS = DNS.deserialize(queryPkt.getData(), queryPkt.getLength());

                int clientPort = queryPkt.getPort();
                InetAddress clientAddress = queryPkt.getAddress();
                
                //drop if not standard query 
                if (queryDNS.getOpcode() != DNS.OPCODE_STANDARD_QUERY) 
                { continue; }
            
                for (DNSQuestion q : queryDNS.getQuestions()) {
                    short type = q.getType();
                    //drop if not A, AA, NS, CNAME
                    if (type != DNS.TYPE_A && type != DNS.TYPE_AAAA && type != DNS.TYPE_NS && type != DNS.TYPE_CNAME) 
                    { continue; }
                    
                    DatagramPacket ansPkt;
                    String origQ = q.getName();
                    try {
                        if (queryDNS.isRecursionDesired()) 
                        { ansPkt = recursive(root, type, q); }
                        else 
                        { ansPkt = nonRecursive(queryPkt, root); }

                        //ansPkt = postprocessPacket(ansPkt, queryPkt, origQ, type);
                        ansPkt.setPort(clientPort);
                        ansPkt.setAddress(clientAddress);
                        socket.send(ansPkt);
                    } catch (Exception e) {
                        System.err.println(e);
					}
                }
        }
    }

    private static DNS dnsFromQuestion(DNSQuestion q) {
        DNS queryDNS = new DNS();
        //queryDNS.setId((short) 33);
        queryDNS.addQuestion(q);
        queryDNS.setQuery(true);
        queryDNS.setRecursionDesired(true);
        queryDNS.setRecursionAvailable(false);
        return queryDNS;
    }

    private static DatagramPacket recursive(InetAddress root, short type, DNSQuestion q) {
        System.out.println("addr: "+root);
        //create new query
        DNS queryDNS = dnsFromQuestion(q);

        byte[] querySerial = queryDNS.serialize();
        DatagramPacket queryPkt = new DatagramPacket(querySerial, querySerial.length);
        
        //request
        DatagramPacket ansPkt = nonRecursive(queryPkt, root);
        DNS ansDNS = DNS.deserialize(ansPkt.getData(), ansPkt.getLength());
        
        System.out.println(ansDNS);

        if (ansDNS.getRcode() != DNS.RCODE_NO_ERROR)
        { return null; }

        List<DNSResourceRecord> answers = ansDNS.getAnswers();
        List<DNSResourceRecord> authorities = ansDNS.getAuthorities();
        List<DNSResourceRecord> additionals = ansDNS.getAdditional();

        for (DNSResourceRecord ans: ansDNS.getAnswers())
        {
            if (ans.getType() == DNS.TYPE_CNAME) {
                boolean isInAnswers = false;
                for (DNSResourceRecord ans2 : ansDNS.getAnswers()) {
                    // check the resolved CNAME is included in answers
                    String name = ans2.getName();
                    String data = ((DNSRdataName) ans.getData()).getName();

                    if (isInAnswers = name.equals(data)) 
                    { break; }
                }
                if (isInAnswers) 
                { continue; }

                /* RESOLVE CNAME HERE*/
                DNSQuestion cnameQuery = new DNSQuestion(((DNSRdataName) ans.getData()).getName(), type);
                DatagramPacket cnamePkt = recursive(root, type, cnameQuery);
                
                if (cnamePkt == null)
                { continue; }

                DNS cnameDNS = DNS.deserialize(cnamePkt.getData(), cnamePkt.getLength());

                answers.addAll(cnameDNS.getAnswers());

                authorities = cnameDNS.getAuthorities();
                additionals = cnameDNS.getAdditional();
            }
        }

        boolean pass = false;
        for (DNSResourceRecord auth : authorities)
        {
            for (DNSResourceRecord add : additionals)
            {
                System.out.println("auth: "+auth+" add: "+add);
                String name = add.getName();
                String data = ((DNSRdataName) auth.getData()).getName();

                if (add.getType() != DNS.TYPE_A || !name.equals(data))
                { continue; }

                DatagramPacket resPkt = recursive(((DNSRdataAddress) add.getData()).getAddress(), type, q);
                if (pass = (resPkt != null))
                { break; }
            }
        }

        queryDNS.setQuery(false);
        queryDNS.setRecursionAvailable(true);
        queryDNS.setAnswers(answers);
        queryDNS.setAuthorities(authorities);
        queryDNS.setAdditional(additionals);
        querySerial = queryDNS.serialize();
        return new DatagramPacket(querySerial, querySerial.length);
        // String name = "";
        // InetAddress nextDst;
        // List<DNSResourceRecord> cnameAnswers = new ArrayList<DNSResourceRecord>();  
        // byte[] buffer = null;   
		// List<DNSResourceRecord> DNSAnswers = new ArrayList<DNSResourceRecord>();
		// List<DNSResourceRecord> authAnswers = new ArrayList<DNSResourceRecord>();
		// // System.out.println("Size of ans : "+ ans.getAnswers().size() + "real answer :: " + ans.g);
        // while (ans.getAnswers().size() == 0 || ans.getAnswers().get(0).getType() == DNS.TYPE_CNAME) {
        //     if (ans.getAdditional().size() > 0) {
        //         for (DNSResourceRecord rec : ans.getAdditional()) {
        //             if (rec.getType() != DNS.TYPE_AAAA && rec.getName().length() > 0) {
        //                 name = rec.getData().toString();
        //                 // break;
		// 				System.out.println("Checking additional ; name = "+ name);

        //             }
        //         }
        //     } 
		// 	if (ans.getAuthorities().size() > 0) {
		// 		System.out.println("checking auth\n");
        //         name = ans.getAuthorities().get(0).getData().toString();
        //     } 
			
		// 	else if (ans.getAnswers().size() == 0) {
		// 		System.out.println("Breaking out");
        //         break;
        //     }

        //     if (ans.getAdditional().size() == 1 && ans.getAdditional().get(0).getName().length() == 0 && ans.getAuthorities().size() == 0 && ans.getAnswers().size() == 0) break;
        //     try {
		// 		System.out.println("checking next");
        //         nextDst = InetAddress.getByName(name);
        //     } catch (Exception e) {
        //         System.err.println(e);
        //         return null;
        //     }
        //     if (ans.getAnswers().size() > 0 && ans.getAnswers().get(0).getType() == DNS.TYPE_CNAME) {
        //         for (DNSResourceRecord cnameAns : ans.getAnswers()) {
        //             cnameAnswers.add(cnameAns);
        //         }
        //         String newQ = ans.getAnswers().get(0).getData().toString();
        //         DNS dnsPkt = DNS.deserialize(queryPkt.getData(), queryPkt.getLength());
        //         DNSQuestion newQuestion = new DNSQuestion(newQ, q.getType());
        //         List<DNSQuestion> DNSQuestions = new ArrayList<DNSQuestion>();
        //         DNSQuestions.add(newQuestion);
        //         dnsPkt.setQuestions(DNSQuestions);
        //         buffer = dnsPkt.serialize();
        //         queryPkt = new DatagramPacket(buffer, buffer.length);
        //     }
        //     ansPkt = nonRecursive(queryPkt, nextDst);
        //     ans = DNS.deserialize(ansPkt.getData(), ansPkt.getLength());
		// 	System.out.println("checking next dest");
        //     //System.out.println(ans+"@@@@@@@@@\n" + ans.getAnswers().size());
			
        //     if(cnameAnswers.size()==0){
		// 	DNSAnswers = ans.getAnswers();
		// 	for (DNSResourceRecord auths : ans.getAuthorities())
		// 		authAnswers.add(auths);
		// 	ans.setAuthorities(authAnswers);
        //     ans.setAnswers(DNSAnswers);
        //     ans.setRecursionAvailable(true);
        //     buffer = ans.serialize();
		// 	ansPkt = new DatagramPacket(buffer, buffer.length);
		// 	System.out.println("set anspacket");
			
		// 	return ansPkt;}

        // }
		
        // if (cnameAnswers.size() > 0) {
		// 	System.out.println("cnameanswers are  : "+ cnameAnswers);
        //     ans = DNS.deserialize(ansPkt.getData(), ansPkt.getLength());
            
        //     for (DNSResourceRecord cnameAns : cnameAnswers) {
        //         DNSAnswers.add(cnameAns);
        //     }
        //     ans.setAnswers(DNSAnswers);
		// 	// ans.setAdditional(ans.getAdditional());
		// 	// ans.setAuthorities(ans.getAuthorities());

        //     System.out.println(ans+"$$$$$$$$$$$$$\n");

        //     buffer = ans.serialize();
        //     ansPkt = new DatagramPacket(buffer, buffer.length);
		
        // }
        // return ansPkt;      
    }

    private static DatagramPacket nonRecursive(DatagramPacket queryPkt, InetAddress dst) {
        try {
            byte[] buffer = new byte[4096];
            DatagramPacket toSend = new DatagramPacket(queryPkt.getData(), queryPkt.getLength(), dst, 53);
            DatagramPacket toReceive = new DatagramPacket(buffer, buffer.length);
            socket.send(toSend);
            socket.receive(toReceive);
            return toReceive;
        } catch (Exception e) {
            System.err.println(e);
        }
        return null;
    }
    
    private static DatagramPacket postprocessPacket(DatagramPacket ansPkt, DatagramPacket queryPkt, String origQ, short type) {
        byte[] buffer = null;       
        DNS ans = DNS.deserialize(ansPkt.getData(), ansPkt.getLength());
        DNSQuestion origQuestion = new DNSQuestion(origQ, type);
        List<DNSQuestion> DNSQuestions = new ArrayList<DNSQuestion>();
        DNSQuestions.add(origQuestion);
        ans.setQuestions(DNSQuestions);
        List<DNSResourceRecord> DNSAnswers = ans.getAnswers();
        List<DNSResourceRecord> updatedDNSAnswers = new ArrayList<DNSResourceRecord>();
        for (DNSResourceRecord dnsAns : DNSAnswers) {
            String ansIp = dnsAns.getData().toString();
            updatedDNSAnswers.add(dnsAns);
            if (dnsAns.getType() != DNS.TYPE_A) continue;
            for (String ip : ipMap.keySet()) {
                if ( (stringToInt(ansIp) ^ stringToInt(ip)) < (((long) 1) << (32 - Integer.parseInt(ipMap.get(ip).get(0))))) {
                    //System.out.println(ansIp+" "+stringToInt(ansIp));
                    //System.out.println(ip+" "+stringToInt(ip));
                    DNSRdata dnsRDataString = new DNSRdataString(String.format("%s-%s", ipMap.get(ip).get(1), ansIp));
                    DNSResourceRecord newAns = new DNSResourceRecord(dnsAns.getName(), DNS.TYPE_TXT, dnsRDataString);
                    updatedDNSAnswers.add(newAns); 
                    break;
                }
            }
        }
        ans.setAnswers(updatedDNSAnswers);
        buffer = ans.serialize();
        ansPkt = new DatagramPacket(buffer, buffer.length);
        return ansPkt;
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

