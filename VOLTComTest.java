import java.io.BufferedReader;
import java.io.FileReader;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.*;

// 测试主类，包含 main 方法
public class VOLTComTest {

    public static void main(String[] args) {
        String filename;
        double epsilon;
        String saveFilename;
        // 如果没有传入参数，使用默认值
        if (args.length < 3) {
            filename = "T-drive3.txt"; // 数据文件或文件路径，确保该文件存在
            epsilon = 1;         // 误差阈值设为 1 米，根据需要调整
            saveFilename = "outputVOLTCom.txt";
        } else {
            filename = args[0];
            epsilon = Double.parseDouble(args[1]);
            saveFilename = args[2];
        }

        try {
            // 1. 读取轨迹数据（支持多种格式）
            List<gpsPoint> points = Readgps.read(filename);
            int originalCount = points.size();
            if (originalCount == 0) {
                System.out.println("未读取到任何 GPS 数据！");
                return;
            }
            System.out.printf("读取到 %d 个 GPS 点.%n", originalCount);

            // 2. 计时执行压缩（调用 extractVectorOrigin 进行向量提取）
            VOLTCom simplificator = new VOLTCom(epsilon);
            long startTime = System.nanoTime();
            List<vector> segments = simplificator.extractVectorOrigin(points);
            long endTime = System.nanoTime();
            double totalTimeMs = (endTime - startTime) / 1e6;  // 毫秒

            // 3. 统计压缩率、平均每点处理时间、误差指标（误差单位转换为米）
            double compressionRatio = (double) segments.size() / originalCount;
            double avgTimePerPointMs = totalTimeMs / originalCount;
            double[] errors = computeErrors(segments, points);  // [平均误差, 最大误差] 单位：米

            // 4. 将每个压缩向量段的起始点信息及性能统计写入输出文件
            try (PrintWriter writer = new PrintWriter(new FileWriter(saveFilename))) {
                for (vector seg : segments) {
                    gpsPoint p = seg.getPstart();
                    writer.printf("%.6f %.6f %d%n", p.getLatitude(), p.getLongitude(), p.getTimestamp());
                }
                writer.printf("Average Compression Ratio (points): %.6f%n", compressionRatio);
                writer.printf("Total Compression Time (ms): %.6f, Average Time per Point (ms): %.6f%n", totalTimeMs, avgTimePerPointMs);
                writer.printf("Average Error (m): %.6f, Maximum Error (m): %.6f%n", errors[0], errors[1]);
            }

            // 5. 同时在控制台输出统计信息
            System.out.println("Compression completed.");
            System.out.printf("Original Points: %d, Compressed Segments: %d%n", originalCount, segments.size());
            System.out.printf("Average Compression Ratio: %.6f%n", compressionRatio);
            System.out.printf("Total Compression Time (ms): %.6f, Average Time per Point (ms): %.6f%n", totalTimeMs, avgTimePerPointMs);
            System.out.printf("Average Error (m): %.6f, Maximum Error (m): %.6f%n", errors[0], errors[1]);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 对于每个原始 GPS 点，根据其时间找到对应的压缩向量段，
     * 计算原始点与该段估计点之间的 haversine 距离，
     * 返回 [平均误差, 最大误差]（单位：米）。
     */
    public static double[] computeErrors(List<vector> segments, List<gpsPoint> points) {
        double totalError = 0.0;
        double maxError = 0.0;
        int count = points.size();
        int segIndex = 0;
        for (gpsPoint p : points) {
            // 寻找覆盖该时间的向量段（假定 segments 按时间升序排列）
            while (segIndex < segments.size() - 1 && p.getTimestamp() > segments.get(segIndex).getETime()) {
                segIndex++;
            }
            gpsPoint estimated = segments.get(segIndex).getEstimate(p.getTimestamp());
            double error = distanceUtils.haversine(p.getLatitude(), p.getLongitude(),
                    estimated.getLatitude(), estimated.getLongitude());
            totalError += error;
            if (error > maxError) {
                maxError = error;
            }
        }
        double avgError = totalError / count;
        return new double[] { avgError, maxError };
    }
}

// ------------------- 以下是各个类的定义 -------------------

// GPS 点类
class gpsPoint {
    private double latitude;
    private double longitude;
    private long timestamp;

    public gpsPoint() { }

    public gpsPoint(double latitude, double longitude, long timestamp) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = timestamp;
    }

    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public long getTimestamp() { return timestamp; }

    public void setLatitude(double latitude) { this.latitude = latitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "gpsPoint(" + latitude + ", " + longitude + ", " + timestamp + ")";
    }
}

// 抽象函数类，定义了估计函数接口
abstract class AbstractFunction {
    protected gpsPoint Pstart;
    protected gpsPoint Pend;

    public abstract gpsPoint estimate(long time);
}

// 一阶贝塞尔函数
class firstBezier extends AbstractFunction {
    public firstBezier(gpsPoint pstart, gpsPoint pend) {
        this.Pstart = pstart;
        this.Pend = pend;
    }

    @Override
    public gpsPoint estimate(long time) {
        double t = (double)(time - Pstart.getTimestamp()) / (Pend.getTimestamp() - Pstart.getTimestamp());
        gpsPoint estPoint = new gpsPoint();
        double estLat = (1 - t) * Pstart.getLatitude() + t * Pend.getLatitude();
        double estLon = (1 - t) * Pstart.getLongitude() + t * Pend.getLongitude();
        estPoint.setLatitude(estLat);
        estPoint.setLongitude(estLon);
        estPoint.setTimestamp(time);
        return estPoint;
    }
}

// 二阶贝塞尔函数
class secondBezier extends AbstractFunction {
    private gpsPoint Pmid;
    public secondBezier(gpsPoint pstart, gpsPoint pmid, gpsPoint pend) {
        this.Pstart = pstart;
        this.Pmid = pmid;
        this.Pend = pend;
    }

    @Override
    public gpsPoint estimate(long time) {
        double t = (double)(time - Pstart.getTimestamp()) / (Pend.getTimestamp() - Pstart.getTimestamp());
        gpsPoint estiPoint = new gpsPoint();
        double estiLat = Math.pow(1 - t, 2) * Pstart.getLatitude()
                + 2 * t * (1 - t) * Pmid.getLatitude()
                + Math.pow(t, 2) * Pend.getLatitude();
        double estiLon = Math.pow(1 - t, 2) * Pstart.getLongitude()
                + 2 * t * (1 - t) * Pmid.getLongitude()
                + Math.pow(t, 2) * Pend.getLongitude();
        estiPoint.setLatitude(estiLat);
        estiPoint.setLongitude(estiLon);
        estiPoint.setTimestamp(time);
        return estiPoint;
    }
}

// 向量类，封装了一个函数及该段的起止时间
class vector {
    private AbstractFunction function;
    private long sTime;
    private long eTime;

    public vector() { }
    public vector(AbstractFunction func, long stime, long etime) {
        this.function = func;
        this.sTime = stime;
        this.eTime = etime;
    }

    public void setETime(long tEnd) {
        this.eTime = tEnd;
    }

    public long getETime() {
        return eTime;
    }

    public gpsPoint getEstimate(long time) {
        return function.estimate(time);
    }

    public gpsPoint getPstart() {
        return getEstimate(sTime);
    }

    public gpsPoint getPend() {
        return getEstimate(eTime);
    }

    @Override
    public String toString() {
        return "vector [sTime=" + sTime + ", eTime=" + eTime
                + ", start=" + getPstart() + ", end=" + getPend() + "]";
    }
}

// 工具类，实现 haversine 距离计算（单位：米）
class distanceUtils {
    public static double haversine(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371000; // 地球半径（米）
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                        Math.sin(dLon/2) * Math.sin(dLon/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}

// 简单接口，定义了简化方法
interface ISimplificator {
    List<gpsPoint> simplify(List<gpsPoint> traj);
}

// VOLTCom 类，实现了 ISimplificator 接口和各类向量提取方法
class VOLTCom implements ISimplificator {
    private double epsilon = 10.0;
    private double sedSum = 0.0;
    private long num = 0;

    public VOLTCom(double epsi) {
        epsilon = epsi;
    }

    @Override
    public List<gpsPoint> simplify(List<gpsPoint> traj) {
        if(traj.size() <= 2) {
            return traj;
        }
        // 该方法未完整实现，直接返回原轨迹
        return traj;
    }

    // 示例：基于平均 SED 判断扩展或产生新向量段
    public List<vector> extractVectorOrigin(List<gpsPoint> traj) {
        List<vector> comTraj = new ArrayList<>();
        boolean init = true;
        gpsPoint first;
        gpsPoint second;
        gpsPoint curPoint;
        vector curVector;

        if(traj.size() == 0) {
            return comTraj;
        } else if(traj.size() == 1) {
            curPoint = traj.get(0);
            vector tmpVector = new vector(new firstBezier(curPoint, curPoint), curPoint.getTimestamp(), curPoint.getTimestamp());
            comTraj.add(tmpVector);
            return comTraj;
        }

        for (int i = 0; i < traj.size(); i++) {
            if (init) {
                first = traj.get(0);
                second = traj.get(1);
                vector tmpVector = new vector(new firstBezier(first, second), first.getTimestamp(), second.getTimestamp());
                comTraj.add(tmpVector);
                num += 2;
                init = false;
            } else {
                curPoint = traj.get(i);
                curVector = comTraj.get(comTraj.size() - 1);
                gpsPoint estimatePoint = curVector.getEstimate(curPoint.getTimestamp());

                double sedCur = distanceUtils.haversine(curPoint.getLatitude(), curPoint.getLongitude(),
                        estimatePoint.getLatitude(), estimatePoint.getLongitude());
                // 如果平均 SED 小于阈值，则扩展当前向量段
                if ((sedSum + sedCur) / (num + 1) < epsilon) {
                    curVector.setETime(curPoint.getTimestamp());
                    sedSum += sedCur;
                    num++;
                } else { // 否则采用二阶贝塞尔生成新的向量段
                    secondBezier tmpFunction = new secondBezier(curVector.getPstart(), curVector.getPend(), curPoint);
                    vector tmpVector = new vector(tmpFunction, curVector.getETime(), curPoint.getTimestamp());
                    comTraj.add(tmpVector);
                    num++;
                }
            }
        }
        return comTraj;
    }

    // 其他方法 extractVectorAlternate、extractVectorOnlyOne、extractVectorOnlyOneAveSed 可类似实现
}

// Readgps 模块：从指定文件中读取 GPS 数据，支持多种格式
class Readgps {
    public static List<gpsPoint> read(String filename) {
        List<gpsPoint> list = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty())
                    continue;
                String[] parts = line.split(",");
                // 根据字段数判断格式
                if (parts.length == 3) {
                    // 格式：时间, 经度, 纬度
                    String timeStr = parts[0].trim();
                    double lon = Double.parseDouble(parts[1].trim());
                    double lat = Double.parseDouble(parts[2].trim());
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date date = sdf.parse(timeStr);
                    long timestamp = date.getTime();
                    list.add(new gpsPoint(lat, lon, timestamp));
                } else if (parts.length >= 7) {
                    // Geolife plt 格式：例如
                    // 40.013867,116.306473,0,226,39744.9868518518,2008-10-23,23:41:04
                    double lat = Double.parseDouble(parts[0].trim());
                    double lon = Double.parseDouble(parts[1].trim());
                    String dateStr = parts[5].trim() + " " + parts[6].trim();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date date = sdf.parse(dateStr);
                    long timestamp = date.getTime();
                    list.add(new gpsPoint(lat, lon, timestamp));
                } else if (parts.length >= 4) {
                    // CSV/txt 格式：例如
                    // 1,2008-02-02 15:36:08,116.51172,39.92123
                    String dateStr = parts[1].trim();
                    double lon = Double.parseDouble(parts[2].trim());
                    double lat = Double.parseDouble(parts[3].trim());
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    Date date = sdf.parse(dateStr);
                    long timestamp = date.getTime();
                    list.add(new gpsPoint(lat, lon, timestamp));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }
}
