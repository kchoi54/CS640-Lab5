import enum
import logging
import llp
import queue
import struct
import threading

class SWPType(enum.IntEnum):
    DATA = ord('D')
    ACK = ord('A')

class SWPPacket:
    _PACK_FORMAT = '!BI'
    _HEADER_SIZE = struct.calcsize(_PACK_FORMAT)
    MAX_DATA_SIZE = 1400 # Leaves plenty of space for IP + UDP + SWP header 

    def __init__(self, type, seq_num, data=b''):
        self._type = type
        self._seq_num = seq_num
        self._data = data
        self.data

    @property
    def type(self):
        return self._type

    @property
    def seq_num(self):
        return self._seq_num
    
    @property
    def data(self):
        return self._data

    def to_bytes(self):
        header = struct.pack(SWPPacket._PACK_FORMAT, self._type.value, 
                self._seq_num)
        return header + self._data
       
    @classmethod
    def from_bytes(cls, raw):
        header = struct.unpack(SWPPacket._PACK_FORMAT,
                raw[:SWPPacket._HEADER_SIZE])
        type = SWPType(header[0])
        seq_num = header[1]
        data = raw[SWPPacket._HEADER_SIZE:]
        return SWPPacket(type, seq_num, data)

    def __str__(self):
        return "%s %d %s" % (self._type.name, self._seq_num, repr(self._data))

class SWPSender:
    _SEND_WINDOW_SIZE = 5
    _TIMEOUT = 1

    def __init__(self, remote_address, loss_probability=0):
        self._llp_endpoint = llp.LLPEndpoint(remote_address=remote_address,
                loss_probability=loss_probability)
        self._llp_lock = threading.Lock() #prevent multiple thread trying to access llp_lock same time

        # Start receive thread
        self._recv_thread = threading.Thread(target=self._recv)
        self._recv_thread.start()

        # TODO: Add additional state variables
        self._window = threading.BoundedSemaphore(value=SWPSender._SEND_WINDOW_SIZE)
        self._buffer = {} # map data with seqNum as key
                          # buffer size will be capped with _window semaphore
        self._timer = {} # map timer object with seqNum as key

        self._last_byte_written = -1
        self._last_byte_acked = -1
        self._last_byte_sent = -1

    def send(self, data):
        for i in range(0, len(data), SWPPacket.MAX_DATA_SIZE):
            self._send(data[i:i+SWPPacket.MAX_DATA_SIZE])

    def _send(self, data):
        #assign seqNum and write to buffer
        self._window.acquire()

        self._last_byte_written+=1
        seq_num = self._last_byte_written
        self._buffer[seq_num] = data

        #send packet
        packet = SWPPacket(SWPType.DATA, seq_num=seq_num, data=data)

        with self._llp_lock:
            self._llp_endpoint.send(packet.to_bytes())
        
        logging.debug("Sent %s" % packet)
        self._last_byte_sent+=1

        #timer retransmission
        t = threading.Timer(self._TIMEOUT, self._retransmit, [seq_num])
        self._timer[seq_num] = t
        t.start()

        return
        
    def _retransmit(self, seq_num):
        data = self._buffer[seq_num]

        #send packet
        import time
        packet = SWPPacket(SWPType.DATA, seq_num=seq_num, data=data)
        with self._llp_lock:
            try:
                self._llp_endpoint.send(packet.to_bytes())
            except ConnectionRefusedError:
                pass #ignore connection refused error (https://piazza.com/class/l75ltmle8rx3md/post/339)
            
        logging.debug("Sent %s", packet)

        #timer retransmission
        t = threading.Timer(SWPSender._TIMEOUT, self._retransmit, [seq_num])
        self._timer[seq_num] = t
        t.start()

        return 

    def _recv(self):
        while True:
            # Receive SWP packet
            raw = self._llp_endpoint.recv()
            if raw is None:
                continue
            packet = SWPPacket.from_bytes(raw)
            logging.debug("Received: %s" % packet)

            seq_num = packet.seq_num
            
            if seq_num > self._last_byte_sent:
                logging.debug("Dropped: %s" % packet)
                continue
            
            self._last_byte_acked = seq_num

            for curr in sorted(self._buffer.keys()):
                if curr <= self._last_byte_acked:
                    #cancel retransmission
                    t = self._timer.pop(curr)
                    t.cancel()

                    #remove data from buffer
                    self._buffer.pop(curr)
                    self._window.release()

        return

class SWPReceiver:
    _RECV_WINDOW_SIZE = 5

    def __init__(self, local_address, loss_probability=0):
        self._llp_endpoint = llp.LLPEndpoint(local_address=local_address, 
                loss_probability=loss_probability)

        # Received data waiting for application to consume
        self._ready_data = queue.Queue()

        # Start receive thread
        self._recv_thread = threading.Thread(target=self._recv)
        self._recv_thread.start()
        
        # TODO: Add additional state variables
        self._buffer = {} # map data with seqNum as key

        self._last_byte_read = -1
        self._last_byte_rcvd = 1
        self._next_byte_expected = 0

    def recv(self):
        return self._ready_data.get()

    def _recv(self):
        while True:
            # Receive data packet
            raw = self._llp_endpoint.recv()
            packet = SWPPacket.from_bytes(raw)
            logging.debug("Received: %s" % packet)
            
            seq_num = packet.seq_num

            #buffer data
            if seq_num > self._last_byte_read or (seq_num - self._last_byte_read) <= SWPReceiver._RECV_WINDOW_SIZE:
                self._last_byte_rcvd = seq_num
                self._buffer[seq_num] = packet.data

            #traverse buffer
            for curr in sorted(self._buffer.keys()):
                if curr == self._last_byte_read+1:
                    self._ready_data.put(self._buffer.pop(curr))
                    self._last_byte_read+=1
                    self._next_byte_expected=curr+1
            
            #send ACK
            packet = SWPPacket(SWPType.ACK, self._next_byte_expected-1)
            self._llp_endpoint.send(packet.to_bytes())
            logging.debug("Sent %s", packet)

            #logging.debug("%s, %s, %s" % (self._last_byte_read, self._last_byte_rcvd, self._next_byte_expected))
            

        return
