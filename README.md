# android-serialport-api
A practical use example of android serial ports

# SerialPortHandler interface to manage the communication between apps and serial port devices
      +-------------------------------------------------------+
      |                                                       |
      |                   Activity class                      |
      |                                                       |
      |  +-------------------------------------------------+  |
      |  |                                                 |  |
      |  |                  UI Thread                      |  |
      |  |                                                 |  |
      |  |    +---------------------------------------+    |  |
      |  |    |                                       |    |  |
      |  |    |             Message Queue             |    |  |
      |  |    |                                       |    |  |
      |  |    |       * MSG_WHAT_RSP                  |    |  |
      |  |    |       * MSG_WHAT_READ_RESULT          |    |  |
      |  |    |                                       |    |  |
      |  |    |                                       |    |  |
      |  |    |                                       |    |  |
      |  +----+ Looper +------------------------------+    |  |
      |  |             |                                   |  |
      |  |   Handler   |                                   |  |
      |  |             |                                   |  |
      |  +-------------+-----------------------------------+  |
      |                                                       |
      +-------------------------------------------------------+

      { obtainMessage }

      +-------------------------------------------------------+
      |                                                       |
      |  +-------------+-----------------------------------+  |
      |  |             |                                   |  |
      |  |   Handler   |                                   |  |
      |  |             |                                   |  |
      |  +----+ Looper +------------------------------+    |  |
      |  |    |                                       |    |  |
      |  |    |             Message Queue             |    |  |
      |  |    |                                       |    |  |
      |  |    |       * MSG_WHAT_READ                 |    |  |
      |  |    |       * MSG_WHAT_WRITE                |    |  |
      |  |    |       * MSG_WHAT_WRITE_AND_READ       |    |  |
      |  |    |       * MSG_WHAT_READ_TERMINATION     |    |  |
      |  |    |                                       |    |  |
      |  |    +---------------------------------------+    |  |
      |  |                                                 |  |
      |  |                 HandlerThread                   |  |
      |  |                                                 |  |
      |  +-------------------------------------------------+  |
      |                                                       |
      |                SerialPortHandler class                |
      |                                                       |
      +-------------------------------------------------------+
      
             { Java: FileInputStream FileOutputStream }
             { JNI:        open close flush           }

      +-------------------------------------------------------+
      |                                                       |
      |                  Serial Port Device                   |
      |                                                       |
      +-------------------------------------------------------+

    /**
     * MSG_WHAT_READ - a reader thread creation
     *
     * Once there is a read, there is a MSG_WHAT_READ_RESULT returning to clients.
     */
    public static final int MSG_WHAT_READ = 0;

    /**
     * MSG_WHAT_WRITE - write commands to the serial port device
     *
     * Normally, this message is not to expect a quick response (ack) from the serial port device,
     * so a MSG_WHAT_READ is often delivered before this message.
     */
    public static final int MSG_WHAT_WRITE = 1;

    /**
     * MSG_WHAT_WRITE_AND_READ - a kind of command/response pair
     *
     * There will be a MSG_WHAT_RSP returning to clients.
     * 
     * This message is not expected to deliver between MSG_WHAT_READ and MSG_WHAT_READ_TERMINATION,
     * or the response is unpredictable.
     */
    public static final int MSG_WHAT_WRITE_AND_READ = 2;

    /**
     * MSG_WHAT_READ_TERMINATION - the reader thread termination
     *
     * To terminate the reader thread which is created by MSG_WHAT_READ
     */
    public static final int MSG_WHAT_READ_TERMINATION = 3;

