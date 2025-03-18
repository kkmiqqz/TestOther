import java.io.*;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class SQUISHTest {

    // 定义轨迹点数据结构（增加 index 字段）
    public static class Point {
        int index;
        double lat;
        double lon;
        double time; // 单位根据数据，可为秒或毫秒

        public Point(int index, double lat, double lon, double time) {
            this.index = index;
            this.lat = lat;
            this.lon = lon;
            this.time = time;
        }
        public Point(double lat, double lon, double time) {
            this.lat = lat;
            this.lon = lon;
            this.time = time;
        }
    }

    // 定义带 SED 误差信息的点
    public static class GPSPointWithSED {
        Point point;
        double sed; // 累计的 SED 误差（单位：米）

        public GPSPointWithSED(Point point, double sed) {
            this.point = point;
            this.sed = sed;
        }
    }

    // 全局存储所有轨迹点
    static List<Point> points = new ArrayList<>();

    /**
     * 从文件中读取轨迹数据
     * 数据格式：每行包含三个数值：纬度 经度 时间（用空白分隔）
     * 注意：C++ 代码中顺序为：lat, lon, time
     */
    public static void gpsReader(String filename) throws IOException, ParseException {
        BufferedReader br = new BufferedReader(new FileReader(filename));
        String line;
        // 使用 SimpleDateFormat 将时间字符串解析成时间戳（毫秒）
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // 数据使用逗号分隔
            String[] parts = line.split(",");
            if (parts.length < 3) continue;
            Date date = sdf.parse(parts[0]);
            double time = (double) date.getTime(); // 毫秒
            // 注意顺序：第二个字段是经度，第三个字段是纬度
            double lon = Double.parseDouble(parts[1]);
            double lat = Double.parseDouble(parts[2]);
            points.add(new SQUISHTest.Point(lat, lon, time));
        }
        br.close();
    }

    /**
     * 计算 SED（同步误差距离，单位：米）
     * 算法：根据点 m 的时间在 s 和 e 之间所占比例，计算线性插值点 p，
     * 然后计算 p 与 m 之间的欧式距离。
     * 为将经纬度差转换为米，采用：
     *   纬度：1度≈111320米，
     *   经度：1度≈111320*cos(meanLat) 米
     */
    public static double calcSED(Point s, Point m, Point e) {
        double numerator = m.time - s.time;
        double denominator = e.time - s.time;
        double timeRatio = (denominator == 0 ? 1 : numerator / denominator);
        double interpLat = s.lat + (e.lat - s.lat) * timeRatio;
        double interpLon = s.lon + (e.lon - s.lon) * timeRatio;
        double latDiff = interpLat - m.lat;
        double lonDiff = interpLon - m.lon;
        double meanLat = (s.lat + m.lat + e.lat) / 3.0;
        double rad = Math.toRadians(meanLat);
        double scaleLat = 111320.0;                  // 米/度（纬度）
        double scaleLon = 111320.0 * Math.cos(rad);    // 米/度（经度）
        return Math.sqrt((latDiff * scaleLat) * (latDiff * scaleLat) +
                (lonDiff * scaleLon) * (lonDiff * scaleLon));
    }

    /**
     * SQUISH 算法：
     * 根据给定的压缩比 cmp_ratio，确定缓冲区大小，
     * 然后依次将轨迹点加入缓冲区，当缓冲区大小超过 max_buffer_size 时，
     * 寻找内部点（不包括首尾）中 sed 最小的点，将该点的 sed 累加到其前后邻居，并将其删除。
     * 返回缓冲区（即简化后的轨迹点）列表。
     */
    public static List<GPSPointWithSED> SQUISH(double cmp_ratio) {
        int maxBufferSize = (int) (cmp_ratio * points.size());
        List<GPSPointWithSED> buffer = new ArrayList<>();
        // 预留空间（可选）
        // 添加第一个点
        buffer.add(new GPSPointWithSED(points.get(0), 0));
        if (maxBufferSize > 2) {
            // 添加第二个点
            buffer.add(new GPSPointWithSED(points.get(1), 0));
            // 从第三个点开始处理
            for (int i = 2; i < points.size(); i++) {
                buffer.add(new GPSPointWithSED(points.get(i), 0));
                // 计算新增点构成的段：前一个点的 sed 累加
                int size = buffer.size();
                Point segStart = buffer.get(size - 3).point;
                Point segEnd = buffer.get(size - 1).point;
                GPSPointWithSED midG = buffer.get(size - 2);
                midG.sed += calcSED(segStart, midG.point, segEnd);
                // 如果缓冲区满了，则寻找内部点中 sed 最小的点进行删除
                if (buffer.size() > maxBufferSize) {
                    int removeIndex = -1;
                    double minSed = Double.MAX_VALUE;
                    // 考虑从 index=1 到 buffer.size()-2（不删除首尾）
                    for (int j = 1; j < buffer.size() - 1; j++) {
                        if (buffer.get(j).sed < minSed) {
                            minSed = buffer.get(j).sed;
                            removeIndex = j;
                        }
                    }
                    // 将被删除点的 sed 累计给其前后邻居
                    if (removeIndex > 0 && removeIndex < buffer.size() - 1) {
                        buffer.get(removeIndex - 1).sed += buffer.get(removeIndex).sed;
                        buffer.get(removeIndex + 1).sed += buffer.get(removeIndex).sed;
                    }
                    // 删除该点
                    buffer.remove(removeIndex);
                }
            }
        } else {
            // 若 maxBufferSize <= 2，则直接添加最后一个点
            buffer.add(new GPSPointWithSED(points.get(points.size() - 1), 0));
        }
        return buffer;
    }

    /**
     * 计算简化结果中（不包括首尾）的平均和最大 SED 误差（单位：米）
     */
    public static double[] computeSEDErrors(List<GPSPointWithSED> buffer) {
        double sum = 0;
        double max = 0;
        int count = 0;
        // 通常首尾点的 sed 为 0，不参与统计
        for (int i = 1; i < buffer.size() - 1; i++) {
            double sed = buffer.get(i).sed;
            sum += sed;
            if (sed > max)
                max = sed;
            count++;
        }
        double avg = count > 0 ? sum / count : 0;
        return new double[]{avg, max};
    }

    public static void main(String[] args) {
        String filename;
        double cmp_ratio;
        String saveFilename;
        // 如果未传入参数，默认：文件 "chengdu"，cmp_ratio = 0.2，输出文件 "output.txt"
        if (args.length < 3) {
            filename = "chengdu5"; // 如有需要，可改为 "T-drive3.txt" 或绝对路径
            cmp_ratio = 0.6;      // 压缩比
            saveFilename = "output.txt";
        } else {
            filename = args[0];
            cmp_ratio = Double.parseDouble(args[1]);
            saveFilename = args[2];
        }

        try {
            // 1. 读取轨迹数据
            gpsReader(filename);
            int originalCount = points.size();

            // 2. 计时并执行 SQUISH 算法（计时单位：毫秒）
            long startTime = System.nanoTime();
            List<GPSPointWithSED> cmpBuffer = SQUISH(cmp_ratio);
            long endTime = System.nanoTime();
            double totalTimeMs = (endTime - startTime) /1000;

            // 3. 统计信息：压缩率、平均每点处理时间、SED 误差信息
            double compressionRatio = (double) cmpBuffer.size() / originalCount;
            double avgTimePerPointMs = totalTimeMs / originalCount;
            double[] sedErrors = computeSEDErrors(cmpBuffer); // [平均SED误差, 最大SED误差]

            // 4. 写入输出文件（简化后的点信息和统计数据）
            PrintWriter writer = new PrintWriter(new FileWriter(saveFilename));
            for (GPSPointWithSED gpsWithSed : cmpBuffer) {
                Point p = gpsWithSed.point;
                writer.printf("%d %.6f %.6f %.6f\n", p.index, p.lat, p.lon, p.time);
            }
            writer.printf("Average Compression Ratio (points): %.6f\n", compressionRatio);
            writer.printf("Total Compression Time (ms): %.6f, Average Time per Point (ms): %.6f\n",
                    totalTimeMs, avgTimePerPointMs);
            writer.printf("Average SED Error (m): %.6f, Maximum SED Error (m): %.6f\n", sedErrors[0], sedErrors[1]);
            writer.close();

            // 同时在控制台输出统计信息
            System.out.println("Compression completed.");
            System.out.printf("Original Points: %d, Simplified Points: %d%n", originalCount, cmpBuffer.size());
            System.out.printf("Average Compression Ratio: %.6f%n", compressionRatio);
            System.out.printf("Total Compression Time (ms): %.6f, Average per Point (ms): %.6f%n", totalTimeMs, avgTimePerPointMs);
            System.out.printf("Average SED Error (m): %.6f, Maximum SED Error (m): %.6f%n", sedErrors[0], sedErrors[1]);

        } catch (IOException e) {
            e.printStackTrace();
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }
}
