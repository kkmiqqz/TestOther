import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.lang.Math;

public class SQUISH_E_Test {

    // 表示轨迹点，记录原始索引、纬度、经度、时间戳（毫秒）
    static class Point {
        int index;
        double lat;
        double lon;
        double time;
        public Point(int index, double lat, double lon, double time) {
            this.index = index;
            this.lat = lat;
            this.lon = lon;
            this.time = time;
        }
        @Override
        public String toString() {
            return String.format("%.6f %.6f %.0f", lat, lon, time);
        }
    }

    // 记录带 SED 值的点（算法中用于排序和删除）
    static class GPSPointWithSED {
        double priority;  // 当前优先级（SED + pi）
        double pi;        // 累积保留的 SED 值
        Point point;
        public GPSPointWithSED(double priority, double pi, Point point) {
            this.priority = priority;
            this.pi = pi;
            this.point = point;
        }
    }

    // 全局变量：轨迹点集合和算法队列 Q
    static List<Point> points = new ArrayList<>();
    static List<GPSPointWithSED> Q = new ArrayList<>();
    // 初始容量控制参数
    static int capacity = 4;

    /**
     * 从文件中读取 GPS 数据，要求文件格式为：
     * 2018-09-30 15:54:03.0,104.09571,30.66221
     * 第一列为时间字符串（格式 "yyyy-MM-dd HH:mm:ss.S"），
     * 第二列为经度，第三列为纬度。
     * 读取后构造 Point 对象，注意构造顺序为：lat, lon, time，且记录原始索引。
     */
    public static void gpsReader(String filename) throws IOException, ParseException {
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            int index = 0;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty())
                    continue;
                String[] parts = line.split(",");
                if (parts.length == 3) {
                    // 原始格式：时间, 经度, 纬度
                    String timeStr = parts[0].trim();
                    double lon = Double.parseDouble(parts[1].trim());
                    double lat = Double.parseDouble(parts[2].trim());
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date date = sdf.parse(timeStr);
                    double timestamp = date.getTime();
                    points.add(new Point(index, lat, lon, timestamp));
                } else if (parts.length >= 7) {
                    // Geolife plt 格式：例如
                    // 40.013867,116.306473,0,226,39744.9868518518,2008-10-23,23:41:04
                    double lat = Double.parseDouble(parts[0].trim());
                    double lon = Double.parseDouble(parts[1].trim());
                    String dateStr = parts[5].trim() + " " + parts[6].trim(); // "2008-10-23 23:41:04"
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date date = sdf.parse(dateStr);
                    double timestamp = date.getTime();
                    points.add(new Point(index, lat, lon, timestamp));
                } else if (parts.length >= 4) {
                    // CSV/txt 格式：例如
                    // 1,2008-02-02 15:36:08,116.51172,39.92123
                    String dateStr = parts[1].trim();
                    double lon = Double.parseDouble(parts[2].trim());
                    double lat = Double.parseDouble(parts[3].trim());
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date date = sdf.parse(dateStr);
                    double timestamp = date.getTime();
                    points.add(new Point(index, lat, lon, timestamp));
                }
                // 如果格式不匹配，则忽略此行
                index++;  // 累加 index 每读取一行数据后
            }
        }
    }

    /**
     * 计算 SED（同步欧氏距离），单位为度
     * 计算公式：
     *   time_ratio = (m.time - s.time) / (e.time - s.time)（若 denominator 为 0 则取 1）
     *   估计点： lat_est = s.lat + (e.lat - s.lat)*time_ratio, lon_est = s.lon + (e.lon - s.lon)*time_ratio
     *   返回： sqrt((lat_est - m.lat)^2 + (lon_est - m.lon)^2)
     */
    public static double calcSED(Point s, Point m, Point e) {
        double numerator = m.time - s.time;
        double denominator = e.time - s.time;
        double time_ratio = (denominator == 0) ? 1 : (numerator / denominator);
        double lat_est = s.lat + (e.lat - s.lat) * time_ratio;
        double lon_est = s.lon + (e.lon - s.lon) * time_ratio;
        double lat_diff = lat_est - m.lat;
        double lon_diff = lon_est - m.lon;
        return Math.sqrt(lat_diff * lat_diff + lon_diff * lon_diff);
    }

    /**
     * 调整队列 Q 中下标 Q_index 处元素的 priority 值
     * 如果 Q_index 是首尾则不调整。
     * 计算公式： priority = pi + calcSED( Q[pre_index].point, Q[Q_index].point, Q[succ_index].point )
     */
    public static void adjustPriority(int pre_index, int Q_index, int succ_index) {
        if (Q_index <= 0 || Q_index >= Q.size() - 1)
            return;
        double p = Q.get(Q_index).pi + calcSED(Q.get(pre_index).point, Q.get(Q_index).point, Q.get(succ_index).point);
        Q.get(Q_index).priority = p;
    }

    /**
     * 在 Q 中删除下标为 min_index 的元素前，
     * 先将其相邻两个点的 pi 值更新为： max(min_p, 原来的 pi)
     * 同时对相邻位置进行 priority 调整，然后删除 Q[min_index]
     */
    public static void reduce(int min_index, double min_p) {
        if (min_index - 1 >= 0)
            Q.get(min_index - 1).pi = Math.max(min_p, Q.get(min_index - 1).pi);
        if (min_index + 1 < Q.size())
            Q.get(min_index + 1).pi = Math.max(min_p, Q.get(min_index + 1).pi);
        if (min_index - 2 >= 0 && min_index - 1 < Q.size() && min_index + 1 < Q.size())
            adjustPriority(min_index - 2, min_index - 1, min_index + 1);
        if (min_index - 1 >= 0 && min_index + 1 < Q.size() && min_index + 2 < Q.size())
            adjustPriority(min_index - 1, min_index + 1, min_index + 2);
        Q.remove(min_index);
    }

    /**
     * 在 Q 中寻找优先级最小的元素的下标（排除首尾）
     */
    public static int findMinPriority() {
        int min_index = 1;
        double minPriority = Q.get(1).priority;
        for (int k = 2; k < Q.size() - 1; k++) {
            if (Q.get(k).priority < minPriority) {
                minPriority = Q.get(k).priority;
                min_index = k;
            }
        }
        return min_index;
    }

    /**
     * SQUISH_E 算法：
     * 参数 cmp_ratio 控制容量增长（这里接受一个数值，可取整），
     * sed_error 为阈值，单位为度（输入的 sed 阈值原为米，经转换后传入）。
     * 算法流程：
     *   - 依次将每个点插入队列 Q；
     *   - 每次当 Q.size() 达到当前 capacity 时，删除优先级最小的点（调用 reduce）；
     *   - 最后对剩余队列中优先级低于 sed_error 的点继续 reduce。
     */
    public static void SQUISH_E(int cmp_ratio, double sed_error) {
        int i = 0;
        while (i < points.size()) {
            if ((i / cmp_ratio) >= capacity) {
                capacity++;
            }
            Q.add(new GPSPointWithSED(Double.MAX_VALUE, 0, points.get(i)));
            if (i > 0 && Q.size() >= 3) {
                adjustPriority(Q.size() - 3, Q.size() - 2, Q.size() - 1);
            }
            if (Q.size() == capacity) {
                int min_index = findMinPriority();
                double min_p = Q.get(min_index).priority;
                reduce(min_index, min_p);
            }
            i++;
        }
        int min_index = findMinPriority();
        double min_p = Q.get(min_index).priority;
        while (min_p <= sed_error && Q.size() > 2) {
            reduce(min_index, min_p);
            if (Q.size() < 3)
                break;
            min_index = findMinPriority();
            min_p = Q.get(min_index).priority;
        }
    }

    /**
     * 计算误差指标：
     * 根据简化后保留点（存储在 Q 中，每个元素的 point.index 为原始点索引），
     * 对于相邻保留点间所有原始点，计算其 SED（单位为度），转换为米后统计平均误差和最大误差。
     */
    public static double[] computeErrors(List<GPSPointWithSED> Q, List<Point> pts, double conversionFactor) {
        List<Integer> idxList = new ArrayList<>();
        for (GPSPointWithSED gps : Q) {
            idxList.add(gps.point.index);
        }
        Collections.sort(idxList);
        double totalError = 0;
        double maxError = 0;
        int count = 0;
        for (int i = 0; i < idxList.size() - 1; i++) {
            int start = idxList.get(i);
            int end = idxList.get(i + 1);
            for (int j = start; j <= end; j++) {
                double errDeg = calcSED(pts.get(start), pts.get(j), pts.get(end));
                double errMeters = errDeg * conversionFactor;
                totalError += errMeters;
                if (errMeters > maxError) {
                    maxError = errMeters;
                }
                count++;
            }
        }
        double avgError = (count > 0) ? totalError / count : 0;
        return new double[]{avgError, maxError};
    }

    public static void main(String[] args) {
        String filename;
        double ratio;       // 压缩比例参数
        double sed_meters;  // 输入的 sed 阈值，单位为米
        String saveFilename;

        if (args.length < 4) {
            filename = "T-drive3.txt";   // 数据文件（放在当前目录下）
            ratio = 1;               // 默认压缩比例参数
            sed_meters = 1;          // 误差阈值，单位：米
            saveFilename = "outputSQ.txt";
        } else {
            filename = args[0];
            ratio = Double.parseDouble(args[1]);
            sed_meters = Double.parseDouble(args[2]);
            saveFilename = args[3];
        }

        try {
            gpsReader(filename);
            int originalCount = points.size();
            if (originalCount == 0) {
                System.out.println("未读取到任何 GPS 数据！");
                return;
            }
            System.out.printf("读取到 %d 个 GPS 点.%n", originalCount);

            double sumLat = 0;
            for (Point p : points) {
                sumLat += p.lat;
            }
            double avgLat = sumLat / originalCount;
            double metersPerDegLat = 111320;
            double metersPerDegLon = 111320 * Math.cos(Math.toRadians(avgLat));
            double conversionFactor = (metersPerDegLat + metersPerDegLon) / 2.0;
            double sedDegrees = sed_meters / conversionFactor;

            long startTime = System.nanoTime();
            SQUISH_E((int)ratio, sedDegrees);
            long endTime = System.nanoTime();
            double totalTimeMs = (endTime - startTime) / 1e6;

            int simplifiedCount = Q.size();
            double compressionRatio = (double) simplifiedCount / originalCount;
            double avgTimePerPointMs = totalTimeMs / originalCount;
            double[] errors = computeErrors(Q, points, conversionFactor);

            try (PrintWriter writer = new PrintWriter(new FileWriter(saveFilename))) {
                for (GPSPointWithSED gps : Q) {
                    writer.println(gps.point);
                }
                writer.printf("Average Compression Ratio (points): %.6f%n", compressionRatio);
                writer.printf("Total Compression Time (ms): %.6f, Average Time per Point (ms): %.6f%n",
                        totalTimeMs, avgTimePerPointMs);
                writer.printf("Average Error (m): %.6f, Maximum Error (m): %.6f%n", errors[0], errors[1]);
            }

            System.out.println("压缩完成。");
            System.out.printf("原始点数: %d, 简化后点数: %d%n", originalCount, simplifiedCount);
            System.out.printf("Average Compression Ratio: %.6f%n", compressionRatio);
            System.out.printf("Total Compression Time (ms): %.6f, Average Time per Point (ms): %.6f%n",
                    totalTimeMs, avgTimePerPointMs);
            System.out.printf("Average Error (m): %.6f, Maximum Error (m): %.6f%n", errors[0], errors[1]);

        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }
    }
}
