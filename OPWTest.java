import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class OPWTest {

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
     * 通用的GPS数据读取方法，支持多种格式：
     *
     * 1. 原始格式（3列）：例如
     *    2018-09-30 15:54:03.0,104.09571,30.66221
     *    第一列为时间字符串（"yyyy-MM-dd HH:mm:ss.S"），第二列为经度，第三列为纬度。
     *
     * 2. Geolife plt 格式（至少7列）：例如
     *    40.013867,116.306473,0,226,39744.9868518518,2008-10-23,23:41:04
     *    使用第一列为纬度、第二列为经度，第六列和第七列合并为时间字符串（"yyyy-MM-dd HH:mm:ss"）。
     *
     * 3. CSV/txt 格式（至少4列）：例如
     *    1,2008-02-02 15:36:08,116.51172,39.92123
     *    使用第二列为时间字符串（"yyyy-MM-dd HH:mm:ss"），第三列为经度，第四列为纬度。
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
                    points.add(new Point(lat, lon, timestamp));
                } else if (parts.length >= 7) {
                    // Geolife plt 格式：例如
                    // 40.013867,116.306473,0,226,39744.9868518518,2008-10-23,23:41:04
                    double lat = Double.parseDouble(parts[0].trim());
                    double lon = Double.parseDouble(parts[1].trim());
                    String dateStr = parts[5].trim() + " " + parts[6].trim(); // "2008-10-23 23:41:04"
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date date = sdf.parse(dateStr);
                    double timestamp = date.getTime();
                    points.add(new Point(lat, lon, timestamp));
                } else if (parts.length >= 4) {
                    // CSV/txt 格式：例如
                    // 1,2008-02-02 15:36:08,116.51172,39.92123
                    String dateStr = parts[1].trim();
                    double lon = Double.parseDouble(parts[2].trim());
                    double lat = Double.parseDouble(parts[3].trim());
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date date = sdf.parse(dateStr);
                    double timestamp = date.getTime();
                    points.add(new Point(lat, lon, timestamp));
                }
                // 如果格式不匹配，则忽略此行
            }
        }
    }

    /**
     * 按照原 C 代码计算 PED（垂直欧氏距离），返回值单位为“度”
     */
    public static double calcPED(Point s, Point m, Point e) {
        double A = e.lon - s.lon;
        double B = s.lat - e.lat;
        double C = e.lat * s.lon - s.lat * e.lon;
        if (A == 0 && B == 0)
            return 0;
        return Math.abs((A * m.lat + B * m.lon + C) / Math.sqrt(A * A + B * B));
    }

    /**
     * OPW 算法实现。
     * 算法流程：
     *   - 从起始点（索引 0）开始，将该点放入结果集合；
     *   - 令 e = originalIndex + 2，然后依次考察 originalIndex 到 e 之间的所有点，
     *     如果发现某点的 PED 超过阈值（epsilon，单位为度），则将该点作为新的起始点，
     *     并更新 e = originalIndex + 2；否则继续增大 e 直至超出轨迹范围。
     *   - 最后将最后一点加入结果集合。
     */
    public static List<Integer> OPW(double epsilon) {
        int originalIndex = 0;
        List<Integer> simplified = new ArrayList<>();
        simplified.add(originalIndex);

        int e = originalIndex + 2;
        while (e < points.size()) {
            int i = originalIndex + 1;
            boolean condOPW = true;
            while (i < e && condOPW) {
                if (calcPED(points.get(originalIndex), points.get(i), points.get(e)) > epsilon)
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
     * 对于简化结果中相邻保留点之间的所有原始点，计算其相对于直线的 PED（单位为度），
     * 再乘以转换系数转换成米，统计平均误差和最大误差。
     */
    public static double[] computeErrors(List<Integer> keptIndices, List<Point> pts, double conversionFactor) {
        Collections.sort(keptIndices);
        double totalError = 0;
        double maxError = 0;
        int count = 0;
        for (int i = 0; i < keptIndices.size() - 1; i++) {
            int start = keptIndices.get(i);
            int end = keptIndices.get(i + 1);
            for (int j = start; j <= end; j++) {
                double errorDeg = calcPED(pts.get(start), pts.get(j), pts.get(end));
                double errorMeters = errorDeg * conversionFactor;
                totalError += errorMeters;
                if (errorMeters > maxError) {
                    maxError = errorMeters;
                }
                count++;
            }
        }
        double avgError = (count > 0) ? totalError / count : 0;
        return new double[] { avgError, maxError };
    }

    public static void main(String[] args) {
        String filename;
        double epsilonMeters;
        String saveFilename;

        // 如果没有传入参数，使用默认值
        if (args.length < 3) {
            filename = "T-drive3.txt";  // 数据文件（或目录下的单个文件）放在当前工作目录下
            epsilonMeters = 1;    // 误差阈值（单位：米）
            saveFilename = "outputOPW.txt";
        } else {
            filename = args[0];
            epsilonMeters = Double.parseDouble(args[1]);
            saveFilename = args[2];
        }

        try {
            // 1. 读取轨迹数据（支持多种格式）
            gpsReader(filename);
            int originalCount = points.size();
            if (originalCount == 0) {
                System.out.println("未读取到任何 GPS 数据！");
                return;
            }
            System.out.printf("读取到 %d 个 GPS 点.%n", originalCount);

            // 2. 根据数据计算平均纬度，从而估算度到米的转换系数
            double sumLat = 0;
            for (Point p : points) {
                sumLat += p.lat;
            }
            double avgLat = sumLat / originalCount;
            double metersPerDegLat = 111320;
            double metersPerDegLon = 111320 * Math.cos(Math.toRadians(avgLat));
            double conversionFactor = (metersPerDegLat + metersPerDegLon) / 2.0;
            // 将输入的 epsilon（米）转换为度
            double epsilonDegrees = epsilonMeters / conversionFactor;

            // 3. 计时执行 OPW 算法
            long startTime = System.nanoTime();
            List<Integer> cmpIndices = OPW(epsilonDegrees);
            long endTime = System.nanoTime();
            double totalTimeMs = (endTime - startTime) / 1e6; // 毫秒

            // 4. 对压缩结果排序并去重（确保结果有序且无重复）
            Set<Integer> cmpSet = new HashSet<>(cmpIndices);
            List<Integer> cmpIndexList = new ArrayList<>(cmpSet);
            Collections.sort(cmpIndexList);

            // 5. 统计性能信息：压缩率、平均每点处理时间、误差指标（单位：米）
            double compressionRatio = (double) cmpIndexList.size() / originalCount;
            double avgTimePerPointMs = totalTimeMs / originalCount;
            double[] errors = computeErrors(cmpIndexList, points, conversionFactor);

            // 6. 将简化后的点和性能统计信息写入输出文件
            try (PrintWriter writer = new PrintWriter(new FileWriter(saveFilename))) {
                for (int idx : cmpIndexList) {
                    Point p = points.get(idx);
                    writer.println(p);
                }
                writer.printf("Average Compression Ratio (points): %.6f%n", compressionRatio);
                writer.printf("Total Compression Time (ms): %.6f, Average Time per Point (ms): %.6f%n",
                        totalTimeMs, avgTimePerPointMs);
                writer.printf("Average Error (m): %.6f, Maximum Error (m): %.6f%n", errors[0], errors[1]);
            }

            // 7. 同时在控制台输出统计信息
            System.out.println("压缩完成。");
            System.out.printf("原始点数: %d, 简化后点数: %d%n", originalCount, cmpIndexList.size());
            System.out.printf("Average Compression Ratio: %.6f%n", compressionRatio);
            System.out.printf("Total Compression Time (ms): %.6f, Average Time per Point (ms): %.6f%n",
                    totalTimeMs, avgTimePerPointMs);
            System.out.printf("Average Error (m): %.6f, Maximum Error (m): %.6f%n", errors[0], errors[1]);

        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }
    }
}
