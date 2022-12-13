JAVAFILE = $(wildcard src/edu/wisc/cs/sdn/simpledns/*/*.java) $(wildcard src/edu/wisc/cs/sdn/simpledns/*.java)
PACKAGE = edu.wisc.cs.sdn.simpledns.SimpleDNS 

CLASSDIR = class

ROOT_IP = 198.41.0.4
EC2_PATH = ec2.csv

compile: $(JAVAFILE)
	javac -d $(CLASSDIR) $(JAVAFILE)

run:
	java -cp $(CLASSDIR) $(PACKAGE) -r $(ROOT_IP) -e $(EC2_PATH) 

clean:
	rm -rf $(CLASSDIR)