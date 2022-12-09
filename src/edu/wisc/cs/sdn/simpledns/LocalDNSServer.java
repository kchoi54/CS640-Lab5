package edu.wisc.cs.sdn.simpledns;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import edu.wisc.cs.sdn.simpledns.packet.DNS;
import edu.wisc.cs.sdn.simpledns.packet.DNSQuestion;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdata;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataAddress;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataName;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataString;
import edu.wisc.cs.sdn.simpledns.packet.DNSResourceRecord;


public class LocalDNSServer{


    private clas EC2AddressRegion{

        String region;
        int subnetmask;
        int ipv4Addr;

        public EC2AddressRegion(int ipv4, int subnet, String region ){
            this.region = region;
            this.subnetmask = subnet;
            this.ipv4Addr = ipv4;
        }


    }


    private InetAddress rootNSAddr;
	private ArrayList<EC2AddressRegion> ec2array;

    public LocalDNSServer(String rootserver, String ec2file) throws UnknownHostException{
        this.rootNSAddr = InetAddress.getByName(rootNameServer);
        this.ec2array = new ArrayList<EC2AddressRegion>();

        //reading ec2file

        BufferedReader bf ; 

        try{
            bf = new BufferedReader(new FileReader(ec2file));
            while(bf.ready()){
                String line = bf.readLine();
                String[] ipmask_reg = line.split(",");
                String[] ipmask = ipmask_reg[0].split("/");


                String region = ipmask_reg[1];
                int ip = ByteBuffer.wrap(InetAddress.getByName(ipmask[0]).getAddress()).getInt();
                short mask = Short.parseShort(ipmask[1]);

                int subnetMask = 0;
				subnetMask = 0xffffffff ^ (1 << 32 - mask) - 1;
				
				ec2array.add(new EC2AddressRegion(ip, subnetMask, region));


            }

            bf.close();

        }

        catch(Exception e){

            System.out.println("Could not read instances of ec2 : \n\n\");

        }




    }



    
}

