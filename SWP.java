/*===============================================================*
 *  File: SWP.java                                               *
 *                                                               *
 *  This class implements the sliding window protocol            *
 *  Used by VMach class					         *
 *  Uses the following classes: SWE, Packet, PFrame, PEvent,     *
 *                                                               *
 *  Author: Professor SUN Chengzheng                             *
 *          School of Computer Engineering                       *
 *          Nanyang Technological University                     *
 *          Singapore 639798                                     *
 *===============================================================*/
import java.util.Timer;
import java.util.TimerTask;

public class SWP {

/*========================================================================*
 the following are provided, do not change them!!
 *========================================================================*/
   //the following are protocol constants.

   public static final int MAX_SEQ = 7;
   public static final int NR_BUFS = (MAX_SEQ + 1) / 2;

   // the following are protocol variables
   private int oldest_frame = 0;
   private PEvent event = new PEvent();  
   private Packet out_buf[] = new Packet[NR_BUFS];

   //the following are used for simulation purpose only
   private SWE swe = null;
   private String sid = null;

   //Constructor
    public SWP(SWE sw, String s) {
      swe = sw;
      sid = s;
   }

   //the following methods are all protocol related
   private void init() {
      for (int i = 0; i < NR_BUFS; i++) {
	   out_buf[i] = new Packet();
      }
   }

   private void wait_for_event(PEvent e) {
      swe.wait_for_event(e); //may be blocked
      oldest_frame = e.seq;  //set timeout frame seq
   }

   private void enable_network_layer(int nr_of_bufs) {
   //network layer is permitted to send if credit is available
	swe.grant_credit(nr_of_bufs);
   }

   private void from_network_layer(Packet p) {
      swe.from_network_layer(p);
   }

   private void to_network_layer(Packet packet) {
	swe.to_network_layer(packet);
   }

    private void to_physical_layer(PFrame fm) {
      System.out.println("SWP: Sending frame: seq = " + fm.seq + 
			    " ack = " + fm.ack + " kind = " + 
                PFrame.KIND[fm.kind] + " info = " + fm.info.data);
      System.out.flush();
      swe.to_physical_layer(fm);
   }

   private void from_physical_layer(PFrame fm) {
    PFrame fm1 = swe.from_physical_layer();
	fm.kind = fm1.kind;
	fm.seq = fm1.seq; 
	fm.ack = fm1.ack;
	fm.info = fm1.info;
   }


/*===========================================================================*
 	implement your Protocol Variables and Methods below:
 *==========================================================================*/
    private boolean no_nak = true; //Variable for absence of negative acknowledgement.

    private Timer[] timer = new Timer[NR_BUFS]; //Array of timers for delay.
    private Timer ack_timer = new Timer(); //Individual timer for acknowledgements.

    static boolean between(int a, int b, int c) { //Compare function to check cyclical nature of the buffer.
        return (((a <= b) && (b < c)) || ((c < a) && (a <= b)) || ((b < c) && (c < a)));
    }

    void send_frame(int fk, int frame_nr, int frame_expected, Packet buffer[]) { //Function to construct and send frame.

        PFrame s = new PFrame();
        s.kind = fk;
        if (fk == PFrame.DATA) {
            s.info = buffer[frame_nr % NR_BUFS];
        }
        s.seq = frame_nr;
        s.ack = (frame_expected + MAX_SEQ) % (MAX_SEQ + 1);
        if (fk == PFrame.NAK){
            no_nak = false;
        }
        to_physical_layer(s);
        if (fk == PFrame.DATA){
            start_timer(frame_nr);
        }
        stop_ack_timer();
    }

    int inc(int num) { //Function to increment to the next frame
        num = (num + 1) % (MAX_SEQ + 1);
        return num;
    }

    public void protocol6() { //The protocol function

        int ack_expected = 0; //Lower edge of sender's window
        int next_frame_to_send = 0; //Upper edge of sender's window + 1
        int frame_expected = 0; //Lower edge of receiver's window
        int too_far = NR_BUFS; //Upper edge of receiver's window  + 1
        int nbuffered = 0; //Number of output buffers currently used
        int i; //Index for buffer pool
        PFrame r = new PFrame();
        init();
        Packet in_buf[] = new Packet[NR_BUFS]; //Buffers for inbound stream
        boolean arrived[] = new boolean[NR_BUFS]; //Inbound bitmap

        enable_network_layer(NR_BUFS); //Initialize the process

        for (i = 0; i < NR_BUFS; i++) {
            arrived[i] = false;
        }


        while (true) {
           wait_for_event(event);
           switch (event.type) {
              case (PEvent.NETWORK_LAYER_READY):
                  nbuffered = nbuffered + 1; //Expands the window
                  from_network_layer(out_buf[next_frame_to_send % NR_BUFS]); //Fetches a new packet to be sent
                  send_frame(PFrame.DATA, next_frame_to_send, frame_expected, out_buf); //Sends the frame
                  next_frame_to_send = inc(next_frame_to_send); //Move the upper edge higher
                  break;

              case (PEvent.FRAME_ARRIVAL):
                  from_physical_layer(r); //Get the incoming frame
                  if (r.kind == PFrame.DATA) { //If frame is undamaged
                      if ((r.seq != frame_expected) && no_nak) {
                          send_frame(PFrame.NAK, 0, frame_expected, out_buf);
                      } else{
                          start_ack_timer();
                      }
                      if (between(frame_expected, r.seq, too_far) && (arrived[r.seq%NR_BUFS]==false)) {
                          arrived[r.seq % NR_BUFS] = true; //Buffer has to be marked full
                          in_buf[r.seq % NR_BUFS] = r.info; //Insert data into the buffer
                          while (arrived[frame_expected % NR_BUFS]) { //Advance the window
                              to_network_layer(in_buf[frame_expected % NR_BUFS]);
                              no_nak = true;
                              arrived[frame_expected % NR_BUFS] = false;
                              frame_expected = inc(frame_expected); //Increase lower edge of receiver's window
                              too_far = inc(too_far); //Increase upper edge of receiver's window
                              start_ack_timer(); //Check if another acknowledgement is required
                          }
                      }
                  }

                  if ((r.kind == PFrame.NAK) && between(ack_expected, (r.ack+1)%(MAX_SEQ+1), next_frame_to_send)){
                      send_frame(PFrame.DATA, (r.ack+1)%(MAX_SEQ+1), frame_expected, out_buf);
                  }

                  while (between(ack_expected, r.ack, next_frame_to_send)) {
                      nbuffered = nbuffered - 1; //Handles the acknowledgement that has been piggybacked
                      stop_timer(ack_expected); //The frame has arrived intact
                      ack_expected = inc(ack_expected); //Increase lower edge of sender's window
                      enable_network_layer(1); //Allow the network layer to send
                  }
                  break;
              case (PEvent.CKSUM_ERR):
                  if (no_nak) send_frame(PFrame.NAK, 0, frame_expected, out_buf);
                  break;
              case (PEvent.TIMEOUT):
                  send_frame(PFrame.DATA, oldest_frame, frame_expected, out_buf);
                  break;
              case (PEvent.ACK_TIMEOUT):
                  send_frame(PFrame.ACK, 0, frame_expected, out_buf);
                  break;
              default:
               System.out.println("SWP: undefined event type = "
                       + event.type);
               System.out.flush();
           }
        }
   }

 /* Note: when start_timer() and stop_timer() are called, 
    the "seq" parameter must be the sequence number, rather 
    than the index of the timer array, 
    of the frame associated with this timer, 
   */

   private void start_timer(int seq) {
       stop_timer(seq); //Stop previous timer
       timer[seq % NR_BUFS] = new Timer(); //Create a new timer
       timer[seq % NR_BUFS].schedule(new TimerTask() { //Start timer
           @Override
           public void run() {
               swe.generate_timeout_event(seq);
           }
       }, 500); //Value for delay
   }

   private void stop_timer(int seq) {
       if(timer[seq % NR_BUFS] != null){ //If timer exists
           timer[seq % NR_BUFS].cancel(); //Cancel the timer
           timer[seq % NR_BUFS].purge(); //Delete it
           timer[seq % NR_BUFS] = null;
       }
   }

   private void start_ack_timer() {
      stop_ack_timer();
      ack_timer = new Timer();
       ack_timer.schedule(new TimerTask() {
           @Override
           public void run() {
               swe.generate_acktimeout_event();
           }
       }, 150); //Value for acknowledgement delay
   }

   private void stop_ack_timer() {
     if(ack_timer != null){
         ack_timer.cancel();
         ack_timer.purge();
         ack_timer = null;
     }
   }


}//End of class

/* Note: In class SWE, the following two public methods are available:
   . generate_acktimeout_event() and
   . generate_timeout_event(seqnr).

   To call these two methods (for implementing timers),
   the "swe" object should be referred as follows:
     swe.generate_acktimeout_event(), or
     swe.generate_timeout_event(seqnr).
*/


