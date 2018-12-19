package org.sofwerx.sqan.manet.packet;

/**
 * Used to break up and re-assemble packets that are larger than the max throughput size for the MANET
 */
public class SegmentTool {
    private static int maxSize;

    /**
     * Sets the max size in bytes that can be passed before a message needs to be segmented
     * @param max max size in bytes
     */
    public static void setMaxPacketSize(int max) { SegmentTool.maxSize = max; }

    /**
     * Will this data fit into a single packet
     * @param size byte size of the data
     * @return true == will fit in a single packet
     */
    public static boolean isSinglePacket(int size) { return (size<maxSize); }

    //FIXME actually segment if needed
}
