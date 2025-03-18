import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class OPW_TR_Test {

    // 内部类：表示轨迹点
    static class Point {
        double lat;   // 纬度
        double lon;   // 经度
        double time;  // 时间戳（毫秒）

        public Point(double lat, double lon, double time) {
            this.lat = lat;
            this.lon = lon;
            this.time = time;
        }

        @Override
        public String toString() {
            return String.format("%.6f %.6f %.0f", lat, lon, time);
        }
    }

    // 全局轨迹点集合
    static List<Point> points = new ArrayList<>();

    /**
     * 从文件中读取 GPS 数据。文件格式为：
     * 2018-09-30 15:54:03.0,104.09571,30.66221
     * 第一列为时间字符串（格式 "yyyy-MM-dd HH:mm:ss.S"），
     * 第二列为经度，第三列为纬度。
     * 读取后构造 Point 对象（顺序：lat, lon, time）。
     */
    public static void gpsReader(String filename) throws IOException, ParseException {
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
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
                    points.add(new OPW_TR_Test.Point(lat, lon, timestamp));
                } else if (parts.length >= 7) {
                    // Geolife plt 格式：例如
                    // 40.013867,116.306473,0,226,39744.9868518518,2008-10-23,23:41:04
                    double lat = Double.parseDouble(parts[0].trim());
                    double lon = Double.parseDouble(parts[1].trim());
                    String dateStr = parts[5].trim() + " " + parts[6].trim(); // "2008-10-23 23:41:04"
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date date = sdf.parse(dateStr);
                    double timestamp = date.getTime();
                    points.add(new OPW_TR_Test.Point(lat, lon, timestamp));
                } else if (parts.length >= 4) {
                    // CSV/txt 格式：例如
                    // 1,2008-02-02 15:36:08,116.51172,39.92123
                    String dateStr = parts[1].trim();
                    double lon = Double.parseDouble(parts[2].trim());
                    double lat = Double.parseDouble(parts[3].trim());
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date date = sdf.parse(dateStr);
                    double timestamp = date.getTime();
                    points.add(new OPW_TR_Test.Point(lat, lon, timestamp));
                }
                // 如果格式不匹配，则忽略此行
            }
        }
    }

    /**
     * 计算 SED（同步欧氏距离），单位为度。
     * 根据公式：
     *   time_ratio = (m.time - s.time) / (e.time - s.time)（若分母为 0 则取 1）
     *   估计点： lat_est = s.lat + (e.lat - s.lat) * time_ratio,
     *            lon_est = s.lon + (e.lon - s.lon) * time_ratio
     * 返回： sqrt((lat_est - m.lat)^2 + (lon_est - m.lon)^2)
     */
    public static double cacl_SED(Point s, Point m, Point e) {
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
     * OPW_TR 算法实现。
     * 传入 epsilon（阈值，单位为度）。
     * 算法流程：
     *   - 从起始点（索引 0）开始，将该点放入结果集合；
     *   - 令 e = originalIndex + 2，然后对 [originalIndex+1, e-1] 内的点检查，
     *     如果发现任一点的 SED > epsilon，则将该点作为新的起始点，并更新 e = originalIndex + 2；
     *     否则 e++；
     *   - 最后将最后一个点加入结果集合。
     */
    public static List<Integer> OPW_TR(double epsilon) {
        int originalIndex = 0;
        List<Integer> simplified = new ArrayList<>();
        simplified.add(originalIndex);
        int e = originalIndex + 2;
        while (e < points.size()) {
            int i = originalIndex + 1;
            boolean condOPW = true;
            while (i < e && condOPW) {
                if (cacl_SED(points.get(originalIndex), points.get(i), points.get(e)) > epsilon)
                    condOPW = false;
                else
                    i++;
            }
            if (!condOPW) {
                originalIndex = i;
                simplified.add(originalIndex);
                e = originalIndex + 2;
            } else {
                e++;
            }
        }
        simplified.add(points.size() - 1);
        return simplified;
    }

    /**
     * 计算误差指标：
     * 对于简化结果中相邻保留点之间的所有原始点，计算其 SED（单位为度），
     * 然后乘以转换系数将误差转换为米，
     * 返回 [平均误差, 最大误差]。
     */
    public static double[] computeErrors(List<Integer> simplifiedIndices, List<Point> pts, double conversionFactor) {
        Collections.sort(simplifiedIndices);
        double totalError = 0;
        double maxError = 0;
        int count = 0;
        for (int i = 0; i < simplifiedIndices.size() - 1; i++) {
            int start = simplifiedIndices.get(i);
            int end = simplifiedIndices.get(i + 1);
            for (int j = start; j <= end; j++) {
                double errDeg = cacl_SED(pts.get(start), pts.get(j), pts.get(end));
                double errMeters = errDeg * conversionFactor;
                totalError += errMeters;
                if (errMeters > maxError)
                    maxError = errMeters;
                count++;
            }
        }
        double avgError = (count > 0) ? totalError / count : 0;
        return new double[]{avgError, maxError};
    }

    public static void main(String[] args) {
        String filename;
        double epsilonMeters;  // 输入的阈值，单位：米
        String saveFilename;

        // 如果没有传入参数，使用默认值
        if (args.length < 3) {
            filename = "T-drive3.txt";   // 数据文件放在当前目录下
            epsilonMeters = 1.0;      // 误差阈值（单位：米）
            saveFilename = "outputTR.txt";
        } else {
            filename = args[0];
            epsilonMeters = Double.parseDouble(args[1]);
            saveFilename = args[2];
        }

        try {
            // 1. 读取轨迹数据
            gpsReader(filename);
            int originalCount = points.size();
            if (originalCount == 0) {
                System.out.println("未读取到任何 GPS 数据！");
                return;
            }
            System.out.printf("读取到 %d 个 GPS 点.%n", originalCount);

            // 2. 根据所有点计算平均纬度，进而估算度到米的转换系数
            double sumLat = 0;
            for (Point p : points) {
                sumLat += p.lat;
            }
            double avgLat = sumLat / originalCount;
            // 每度纬度约 111320 米，每度经度约 111320×cos(avgLat) 米
            double metersPerDegLat = 111320;
            double metersPerDegLon = 111320 * Math.cos(Math.toRadians(avgLat));
            double conversionFactor = (metersPerDegLat + metersPerDegLon) / 2.0;
            // 将输入的 epsilon（米）转换为度
            double epsilonDegrees = epsilonMeters / conversionFactor;

            // 3. 计时执行 OPW_TR 算法
            long startTime = System.nanoTime();
            List<Integer> simplifiedIndices = OPW_TR(epsilonDegrees);
            long endTime = System.nanoTime();
            double totalTimeMs = (endTime - startTime) / 1e6; // 毫秒

            // 4. 统计性能信息
            int simplifiedCount = simplifiedIndices.size();
            double compressionRatio = (double) simplifiedCount / originalCount;
            double avgTimePerPointMs = totalTimeMs / originalCount;
            double[] errors = computeErrors(simplifiedIndices, points, conversionFactor);

            // 5. 将简化后的点及性能信息写入输出文件
            try (PrintWriter writer = new PrintWriter(new FileWriter(saveFilename))) {
                for (int idx : simplifiedIndices) {
                    writer.println(points.get(idx));
                }
                writer.printf("Average Compression Ratio (points): %.6f%n", compressionRatio);
                writer.printf("Total Compression Time (ms): %.6f, Average Time per Point (ms): %.6f%n",
                        totalTimeMs, avgTimePerPointMs);
                writer.printf("Average Error (m): %.6f, Maximum Error (m): %.6f%n", errors[0], errors[1]);
            }

            // 6. 同时在控制台输出统计信息
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
