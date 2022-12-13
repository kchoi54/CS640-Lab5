JAVAFILE = $(wildcard src/edu/wisc/cs/sdn/simpledns/*/*.java) $(wildcard src/edu/wisc/cs/sdn/simpledns/*.java)
CLASSFILE = $(wildcard src/edu/wisc/cs/sdn/simpledns/*/*.class) $(wildcard src/edu/wisc/cs/sdn/simpledns/*.class)

MAIN = src.edu.wisc.cs.sdn.simpledns.SimpleDNS #this is not running properly can you check if you can fix this

ROOT_IP = 198.41.0.4
EC2_PATH = ../ec2.csv

compile: $(JAVAFILE)
	javac $(JAVAFILE)

run: #this is not running properly can you check if you can fix this
	java $(MAIN) -r $(ROOT_IP) -e $(EC2_PATH) 

clean: #this also does not rm LocalDNSServer$EC2AddressRegion.class somehow
	rm -f $(CLASSFILE)