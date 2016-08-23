package com.digitalpebble.stormcrawler;

import org.apache.storm.hdfs.bolt.rotation.FileRotationPolicy;
import org.apache.storm.hdfs.bolt.rotation.FileSizeRotationPolicy;
import org.apache.storm.tuple.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Rotates a file based on size or optionally time, whichever occurs first **/
public class FileTimeSizeRotationPolicy implements FileRotationPolicy {

    private static final Logger LOG = LoggerFactory
            .getLogger(FileSizeRotationPolicy.class);

    public static enum Units {

        KB((long) Math.pow(2, 10)), MB((long) Math.pow(2, 20)), GB(
                (long) Math.pow(2, 30)), TB((long) Math.pow(2, 40));

        private long byteCount;

        private Units(long byteCount) {
            this.byteCount = byteCount;
        }

        public long getByteCount() {
            return byteCount;
        }
    }

    public static enum TimeUnit {

        SECONDS((long) 1000), MINUTES((long) 1000 * 60), HOURS(
                (long) 1000 * 60 * 60), DAYS((long) 1000 * 60 * 60 * 24);

        private long milliSeconds;

        private TimeUnit(long milliSeconds) {
            this.milliSeconds = milliSeconds;
        }

        public long getMilliSeconds() {
            return milliSeconds;
        }
    }

    private long interval = -1;

    private long maxBytes;

    private long lastOffset = 0;
    private long currentBytesWritten = 0;

    private long timeStarted = System.currentTimeMillis();

    public FileTimeSizeRotationPolicy(float count, Units units) {
        this.maxBytes = (long) (count * units.getByteCount());
    }

    public void setTimeRotationInterval(float count, TimeUnit units) {
        this.interval = (long) (count * units.getMilliSeconds());
    }

    @Override
    public boolean mark(Tuple tuple, long offset) {
        // check based on time first
        if (interval != -1) {
            long now = System.currentTimeMillis();
            if (now >= timeStarted + interval) {
                LOG.info("Rotating file based on time : started {} interval {}",
                        timeStarted, interval);
                return true;
            }
        }

        long diff = offset - this.lastOffset;
        this.currentBytesWritten += diff;
        this.lastOffset = offset;
        boolean size = this.currentBytesWritten >= this.maxBytes;
        if (size) {
            LOG.info(
                    "Rotating file based on size : currentBytesWritten {} maxBytes {}",
                    currentBytesWritten, maxBytes);
        }
        return size;
    }

    @Override
    public void reset() {
        this.currentBytesWritten = 0;
        this.lastOffset = 0;
        this.timeStarted = System.currentTimeMillis();
    }

}
