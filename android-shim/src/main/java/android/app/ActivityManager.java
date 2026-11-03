package android.app;

import java.lang.management.ManagementFactory;

/** Reports real physical memory so plugins that size concurrency by RAM behave sensibly. */
public class ActivityManager {
    public ActivityManager() { }

    public void getMemoryInfo(MemoryInfo info) {
        long total = Runtime.getRuntime().maxMemory();
        long free = Runtime.getRuntime().freeMemory();
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean s = (com.sun.management.OperatingSystemMXBean) os;
            total = s.getTotalMemorySize();
            free = s.getFreeMemorySize();
        }
        info.totalMem = total;
        info.availMem = free;
        info.lowMemory = false;
    }

    public boolean isLowRamDevice() { return false; }
    public int getMemoryClass() { return (int) (Runtime.getRuntime().maxMemory() / (1024 * 1024)); }

    public static class MemoryInfo {
        public long availMem;
        public long totalMem;
        public long threshold;
        public boolean lowMemory;

        public MemoryInfo() { }
    }
}
