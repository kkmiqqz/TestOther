import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class TestDouglasPeucker {

    // 内部类：表示轨迹点
    static class Point {
        double lat;
        double lon;
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
     * 从文件中读取 GPS 数据，文件格式为：
     * 2018-09-30 15:54:03.0,104.09571,30.66221
     * 第一列为时间字符串（格式 "yyyy-MM-dd HH:mm:ss.S"），
     * 第二列为经度，第三列为纬度。
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
                    points.add(new TestDouglasPeucker.Point(lat, lon, timestamp));
                } else if (parts.length >= 7) {
                    // Geolife plt 格式：例如
                    // 40.013867,116.306473,0,226,39744.9868518518,2008-10-23,23:41:04
                    double lat = Double.parseDouble(parts[0].trim());
                    double lon = Double.parseDouble(parts[1].trim());
                    String dateStr = parts[5].trim() + " " + parts[6].trim(); // "2008-10-23 23:41:04"
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date date = sdf.parse(dateStr);
                    double timestamp = date.getTime();
                    points.add(new TestDouglasPeucker.Point(lat, lon, timestamp));
                } else if (parts.length >= 4) {
                    // CSV/txt 格式：例如
                    // 1,2008-02-02 15:36:08,116.51172,39.92123
                    String dateStr = parts[1].trim();
                    double lon = Double.parseDouble(parts[2].trim());
                    double lat = Double.parseDouble(parts[3].trim());
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date date = sdf.parse(dateStr);
                    double timestamp = date.getTime();
                    points.add(new TestDouglasPeucker.Point(lat, lon, timestamp));
                }
                // 如果格式不匹配，则忽略此行
            }
        }
    }

    /**
     * 按照原 C 代码计算 PED（垂直欧氏距离），单位为“度”
     */
    public static double calcPED(Point s, Point m, Point e) {
        double A = e.lon - s.lon;
        double B = s.lat - e.lat;
        double C = e.lat * s.lon - s.lat * e.lon;
        if (A == 0 && B == 0)
            return 0;
        double ped = Math.abs((A * m.lat + B * m.lon + C) / Math.sqrt(A * A + B * B));
        return ped;
    }

    /**
     * Douglas–Peucker 算法（递归实现）。
     * 参数 start 与 end 为 points 列表中当前段的起始和终止索引，
     * 如果当前段内最大 PED 大于 epsilon（此 epsilon 单位为度），则递归细分，
     * 否则保留该段两个端点。
     */
    public static List<Integer> DouglasPeucker(int start, int end, double epsilon) {
        double dmax = 0;
        int index = start;
        List<Integer> recResults = new ArrayList<>();
        for (int i = start + 1; i < end; i++) {
            double d = calcPED(points.get(start), points.get(i), points.get(end));
            if (d > dmax) {
                index = i;
                dmax = d;
            }
        }
        if (dmax > epsilon) {
            List<Integer> recResults1 = DouglasPeucker(start, index, epsilon);
            List<Integer> recResults2 = DouglasPeucker(index, end, epsilon);
            recResults.addAll(recResults1);
            recResults.addAll(recResults2);
        } else {
            recResults.add(start);
            recResults.add(end);
        }
        return recResults;
    }

    /**
     * 计算误差指标。
     * 对于简化结果中相邻保留点之间的所有原始点，计算其相对于直线的 PED（单位为度），
     * 最后乘以转换系数将误差转换为米，返回 [平均误差, 最大误差]。
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

        // 如果没有传入参数，使用默认值：
        if (args.length < 3) {
            filename = "chengdu5"; // 数据文件放在当前工作目录下
            epsilonMeters = 1.0;   // 输入误差阈值，单位：米
            saveFilename = "outputDP.txt";
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

            // 2. 根据数据计算平均纬度，进而估算度到米的转换系数
            double sumLat = 0;
            for (Point p : points) {
                sumLat += p.lat;
            }
            double avgLat = sumLat / originalCount;
            // 每度纬度约 111320 米，每度经度约 111320×cos(avgLat) 米
            double metersPerDegLat = 111320;
            double metersPerDegLon = 111320 * Math.cos(Math.toRadians(avgLat));
            double conversionFactor = (metersPerDegLat + metersPerDegLon) / 2.0;
            // 将输入的 ε（单位米）转换为度
            double epsilonDegrees = epsilonMeters / conversionFactor;

            // 3. 计时执行 Douglas–Peucker 算法
            long startTime = System.nanoTime();
            List<Integer> cmpIndices = DouglasPeucker(0, originalCount - 1, epsilonDegrees);
            long endTime = System.nanoTime();
            double totalTimeMs = (endTime - startTime) / 1e6;  // 毫秒

            // 4. 对压缩结果排序并去重
            Set<Integer> cmpSet = new HashSet<>(cmpIndices);
            List<Integer> cmpIndexList = new ArrayList<>(cmpSet);
            Collections.sort(cmpIndexList);

            // 5. 统计性能指标
            double compressionRatio = (double) cmpIndexList.size() / originalCount;
            double avgTimePerPointMs = totalTimeMs / originalCount;
            double[] errors = computeErrors(cmpIndexList, points, conversionFactor);  // 单位：米

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
