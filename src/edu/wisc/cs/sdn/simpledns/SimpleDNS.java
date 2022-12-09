package edu.wisc.cs.sdn.simpledns;

public class SimpleDNS 
{
	public static void main(String[] args)
	{

		//adding code for a functioning DNS Server 

		if(args.length == 4 && args[0].contentEquals("-r") && args[2].contentEquals("-e")){

			try{
				LocalDNSServer dnsserver = new LocalDNSServer(args[1],args[3]);
				dnsserver.runDNSServer();
			}
			catch(Exception e){
				e.printStackTrace();
			}


		}

		else {
			System.out.println("Usage : java edu.wisc.cs.sdn.simpledns.SimpleDNS -r <root server ip> -e <ec2 csv>");
			System.exit(-1);
		}
		

       
	}
}
