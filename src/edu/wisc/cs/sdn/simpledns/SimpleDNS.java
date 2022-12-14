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
import java.net.InetSocketAddress;

public class SimpleDNS 
{
    public static DatagramSocket socket;
    public static Map<String, List<String>> ipMap = new HashMap<String, List<String>>();
    public static InetAddress root;
    public static void main(String[] args) throws Exception
    {
        System.out.println("Hello, DNS!"); 
        if (args.length != 4) {
            System.err.println("Usage: java edu.wisc.cs.sdn.simpledns.SimpleDNS -r <root server ip> -e <ec2 csv>");
            return;
        }       
        String rootIp = args[1];
        String file = args[3];

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String city = line.split(",")[1];
                String [] ip = line.split(",")[0].split("/");
                List<String> entry = new ArrayList<String>();
                entry.add(ip[1]);
                entry.add(city);
                ipMap.put(ip[0], entry);    
            }
        } catch (Exception e) {
            System.err.println(e);
        }

        root = InetAddress.getByName(rootIp);
        socket = new DatagramSocket(8053);
        byte[] buffer = new byte[4096];
        while (true) {
                DatagramPacket queryPkt = new DatagramPacket(buffer, buffer.length);
                socket.receive(queryPkt);
                //System.out.println()
                DNS dnsPkt = DNS.deserialize(queryPkt.getData(), queryPkt.getLength());
                if (dnsPkt.getOpcode() != DNS.OPCODE_STANDARD_QUERY) return;
                for (DNSQuestion q : dnsPkt.getQuestions()) {
                    short type = q.getType();
                    if (type != DNS.TYPE_A && type != DNS.TYPE_NS && type != DNS.TYPE_CNAME && type != DNS.TYPE_AAAA) return;
                    DatagramPacket ansPkt;
                    String origQ = q.getName();
                    try {
                        if (dnsPkt.isRecursionDesired()) ansPkt = recursive(queryPkt, root, q);
                        else ansPkt = nonRecursive(queryPkt, root);
                        ansPkt = postprocessPacket(ansPkt, queryPkt, origQ, type);
                        ansPkt.setPort(queryPkt.getPort());
                        ansPkt.setAddress(InetAddress.getByName("localhost"));
                        socket.send(ansPkt);
                    } catch (Exception e) {
                        System.err.println(e);
					}
                }
        }
    }
    private static DatagramPacket recursive(DatagramPacket queryPkt, InetAddress root, DNSQuestion q) {
		System.out.println("Recursing\n\n");
        DatagramPacket ansPkt = nonRecursive(queryPkt, root);
		// queryPkt.setRecursionAvailable() = true ;
        DNS ans = DNS.deserialize(ansPkt.getData(), ansPkt.getLength());
        String name = "";
        InetAddress nextDst;
        List<DNSResourceRecord> cnameAnswers = new ArrayList<DNSResourceRecord>();  
        byte[] buffer = null;   
		List<DNSResourceRecord> DNSAnswers = new ArrayList<DNSResourceRecord>();
		List<DNSResourceRecord> authAnswers = new ArrayList<>();
        List<DNSResourceRecord> additionalAnswers = new ArrayList<>();
		// System.out.println("Size of ans : "+ ans.getAnswers().size() + "real answer :: " + ans.g);
        while (ans.getAnswers().size() == 0) {
            if (ans.getAdditional().size() > 0) {
                for (DNSResourceRecord rec : ans.getAdditional()) {
                    if (rec.getType() != DNS.TYPE_AAAA && rec.getName().length() > 0) {
                        name = rec.getData().toString();
                        // break;
						System.out.println("Checking additional ; name = "+ name);

                    }
                }
            } 
			if (ans.getAuthorities().size() > 0) {
				System.out.println("checking auth\n");
                name = ans.getAuthorities().get(0).getData().toString();
            } 
			
			else if (ans.getAnswers().size() == 0) {
				System.out.println("Breaking out");
                break;
            }

            if (ans.getAdditional().size() == 1 && ans.getAdditional().get(0).getName().length() == 0 && ans.getAuthorities().size() == 0 && ans.getAnswers().size() == 0) break;
            try {
				System.out.println("checking next");
                nextDst = InetAddress.getByName(name);
            } catch (Exception e) {
                System.err.println(e);
                return null;
            }
            ansPkt = nonRecursive(queryPkt, nextDst);
            ans = DNS.deserialize(ansPkt.getData(), ansPkt.getLength());
			System.out.println("checking next dest");
            //System.out.println(ans+"@@@@@@@@@\n" + ans.getAnswers().size() + " " + ans.getAuthorities() + "Addtitionals : " + ans.getAdditional());
            authAnswers.addAll(ans.getAuthorities());
            additionalAnswers.addAll(ans.getAdditional());
        }

        System.out.println(ans.getAnswers());
        if (ans.getAnswers().size() > 0) {
            for (DNSResourceRecord ansEntry : ans.getAnswers()) {
                if(ansEntry.getType() == DNS.TYPE_CNAME) {
                    DNSRdataName data = (DNSRdataName) ansEntry.getData();
                    String addr = data.getName();
                    boolean found = false;
                    for (DNSResourceRecord ansEntry2 : ans.getAnswers()) {
                        if(ansEntry2.getName().equals(addr)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found)
                    {cnameAnswers.add(ansEntry);}
                }
            }
        }
        
        DNSAnswers.addAll(ans.getAnswers());
        if (q.getType() == DNS.TYPE_A) {
            for (DNSResourceRecord cnameEntry:cnameAnswers) {

                String newQ = cnameEntry.getData().toString();
                DNS dnsPkt = DNS.deserialize(queryPkt.getData(), queryPkt.getLength());
                DNSQuestion newQuestion = new DNSQuestion(newQ, q.getType());
                List<DNSQuestion> DNSQuestions = new ArrayList<DNSQuestion>();
                DNSQuestions.add(newQuestion);
                dnsPkt.setQuestions(DNSQuestions);
                buffer = dnsPkt.serialize();
                queryPkt = new DatagramPacket(buffer, buffer.length);

                DatagramPacket cnameAnsPkt = recursive(queryPkt, root, newQuestion);
                DNS cnameAns = DNS.deserialize(cnameAnsPkt.getData(), cnameAnsPkt.getLength());
                DNSAnswers.addAll(cnameAns.getAnswers());
            }
        }
		
  
			// System.out.println("cnameanswers are  : "+ cnameAnswers);
            // ans = DNS.deserialize(ansPkt.getData(), ansPkt.getLength());

            // DNSAnswers.addAll(ans.getAnswers());
            
            // for (DNSResourceRecord cnameAns : cnameAnswers) {
            //     DNSAnswers.add(cnameAns);
            // }
            ans.setAnswers(DNSAnswers);
            // for item : authAnswers{
                
            // }
            ans.setAuthorities(authAnswers);
            List<DNSResourceRecord> finadd = new ArrayList<>();
            for (DNSResourceRecord ele : additionalAnswers){
                if (ele.getType() == DNS.TYPE_A || ele.getType() == DNS.TYPE_AAAA)
                    finadd.add(ele);
            }

            ans.setAdditional(finadd);
			// ans.setAdditional(ans.getAdditional());
			// ans.setAuthorities(ans.getAuthorities());

            ans.setRecursionAvailable(true);
            ans.setRecursionDesired(false);
            ans.setAuthenicated(false);
            ans.setAuthoritative(false);

            System.out.println(ans+"$$$$$$$$$$$$$\n");
            buffer = ans.serialize();
            ansPkt = new DatagramPacket(buffer, buffer.length);		
        
        return ansPkt;      
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
        DNS query = DNS.deserialize(queryPkt.getData(), queryPkt.getLength());
        ans.addAdditional(query.getAdditional().get(0)); //pass opt PSEUDOSECTION back to client
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

