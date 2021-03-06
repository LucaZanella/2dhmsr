/*
 * Copyright (C) 2020 Eric Medvet <eric.medvet@gmail.com> (as eric)
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.units.erallab.hmsrobots.validation;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import it.units.erallab.hmsrobots.objects.Ground;
import it.units.erallab.hmsrobots.objects.Robot;
import it.units.erallab.hmsrobots.objects.Voxel;
import it.units.erallab.hmsrobots.objects.WorldObject;
import it.units.erallab.hmsrobots.objects.immutable.BoundingBox;
import it.units.erallab.hmsrobots.objects.immutable.Point2;
import it.units.erallab.hmsrobots.objects.immutable.Snapshot;
import it.units.erallab.hmsrobots.tasks.AbstractTask;
import it.units.erallab.hmsrobots.util.Grid;
import it.units.erallab.hmsrobots.viewers.SnapshotListener;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.dyn4j.dynamics.Settings;
import org.dyn4j.dynamics.World;
import org.dyn4j.dynamics.joint.WeldJoint;
import org.dyn4j.geometry.Vector2;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * @author Eric Medvet <eric.medvet@gmail.com>
 */
public class CantileverBending extends AbstractTask<Grid<Voxel.Description>, CantileverBending.Result> {

  public static class Result {

    private final double realTime;
    private final double dampingRealTime;
    private final double dampingSimTime;
    private final long steps;
    private final long dampingSteps;
    private final double dampingVoxelStepsPerSecond;
    private final double dampingVoxelSimSecondsPerSecond;
    private final double dampingStepsPerSecond;
    private final double overallVoxelStepsPerSecond;
    private final double overallVoxelSimSecondsPerSecond;
    private final double overallStepsPerSecond;
    private final double yDisplacement;
    private final Map<String, List<Double>> timeEvolution;
    private final List<Point2> finalTopPositions;

    public Result(double realTime, double dampingRealTime, double dampingSimTime, long steps, long dampingSteps, double dampingVoxelStepsPerSecond, double dampingVoxelSimSecondsPerSecond, double dampingStepsPerSecond, double overallVoxelStepsPerSecond, double overallVoxelSimSecondsPerSecond, double overallStepsPerSecond, double yDisplacement, Map<String, List<Double>> timeEvolution, List<Point2> finalTopPositions) {
      this.realTime = realTime;
      this.dampingRealTime = dampingRealTime;
      this.dampingSimTime = dampingSimTime;
      this.steps = steps;
      this.dampingSteps = dampingSteps;
      this.dampingVoxelStepsPerSecond = dampingVoxelStepsPerSecond;
      this.dampingVoxelSimSecondsPerSecond = dampingVoxelSimSecondsPerSecond;
      this.dampingStepsPerSecond = dampingStepsPerSecond;
      this.overallVoxelStepsPerSecond = overallVoxelStepsPerSecond;
      this.overallVoxelSimSecondsPerSecond = overallVoxelSimSecondsPerSecond;
      this.overallStepsPerSecond = overallStepsPerSecond;
      this.yDisplacement = yDisplacement;
      this.timeEvolution = timeEvolution;
      this.finalTopPositions = finalTopPositions;
    }

    public double getRealTime() {
      return realTime;
    }

    public double getDampingRealTime() {
      return dampingRealTime;
    }

    public double getDampingSimTime() {
      return dampingSimTime;
    }

    public long getSteps() {
      return steps;
    }

    public long getDampingSteps() {
      return dampingSteps;
    }

    public double getDampingVoxelStepsPerSecond() {
      return dampingVoxelStepsPerSecond;
    }

    public double getDampingVoxelSimSecondsPerSecond() {
      return dampingVoxelSimSecondsPerSecond;
    }

    public double getDampingStepsPerSecond() {
      return dampingStepsPerSecond;
    }

    public double getOverallVoxelStepsPerSecond() {
      return overallVoxelStepsPerSecond;
    }

    public double getOverallVoxelSimSecondsPerSecond() {
      return overallVoxelSimSecondsPerSecond;
    }

    public double getOverallStepsPerSecond() {
      return overallStepsPerSecond;
    }

    public double getyDisplacement() {
      return yDisplacement;
    }

    public Map<String, List<Double>> getTimeEvolution() {
      return timeEvolution;
    }

    public List<Point2> getFinalTopPositions() {
      return finalTopPositions;
    }

  }

  private final static double WALL_MARGIN = 10d;

  private final double force;
  private final double forceDuration;
  private final double finalT;
  private final double epsilon;

  public CantileverBending(double force, double forceDuration, double finalT, double epsilon, Settings settings) {
    super(settings);
    this.force = force;
    this.forceDuration = forceDuration;
    this.finalT = finalT;
    this.epsilon = epsilon;
  }

  @Override
  public Result apply(Grid<Voxel.Description> voxelDescriptionGrid, SnapshotListener listener) {
    List<WorldObject> worldObjects = new ArrayList<>();
    //build voxel compound
    Robot robot = new Robot(0, 0, new Robot.Description(
        voxelDescriptionGrid, null
    ));
    BoundingBox boundingBox = robot.boundingBox();
    worldObjects.add(robot);
    //build ground
    Ground ground = new Ground(new double[]{0, 1}, new double[]{0, boundingBox.max.y - boundingBox.min.y + 2d * WALL_MARGIN});
    worldObjects.add(ground);
    //build world w/o gravity
    World world = new World();
    world.setSettings(settings);
    world.setGravity(new Vector2(0d, 0d));
    for (WorldObject worldObject : worldObjects) {
      worldObject.addTo(world);
    }
    //attach vc to ground
    robot.translate(new Vector2(-boundingBox.min.x + 1d, (boundingBox.max.y - boundingBox.min.y + 2d * WALL_MARGIN) / 2d - 1d));
    for (int y = 0; y < robot.getVoxels().getH(); y++) {
      for (int i : new int[]{0, 3}) {
        WeldJoint joint = new WeldJoint(
            ground.getBodies().get(0),
            robot.getVoxels().get(0, y).getVertexBodies()[i],
            robot.getVoxels().get(0, y).getVertexBodies()[i].getWorldCenter()
        );
        world.addJoint(joint);
      }
    }
    //prepare data
    List<Double> ys = new ArrayList<>((int) Math.round(finalT / settings.getStepFrequency()));
    List<Double> realTs = new ArrayList<>((int) Math.round(finalT / settings.getStepFrequency()));
    List<Double> simTs = new ArrayList<>((int) Math.round(finalT / settings.getStepFrequency()));
    double y0 = robot.getVoxels().get(robot.getVoxels().getW() - 1, robot.getVoxels().getH() / 2).getCenter().y;
    //simulate
    Stopwatch stopwatch = Stopwatch.createStarted();
    double t = 0d;
    while (t < finalT) {
      //add force
      if (t <= forceDuration) {
        for (int y = 0; y < robot.getVoxels().getH(); y++) {
          for (int i : new int[]{1, 2}) {
            robot.getVoxels().get(robot.getVoxels().getW() - 1, y).getVertexBodies()[i].applyForce(new Vector2(0d, -force / 2d / robot.getVoxels().getH()));
          }
        }
      }
      //do step
      t = t + settings.getStepFrequency();
      world.step(1);
      if (listener != null) {
        Snapshot snapshot = new Snapshot(t, worldObjects.stream().map(WorldObject::immutable).collect(Collectors.toList()));
        listener.listen(snapshot);
      }
      //get position
      double y = robot.getVoxels().get(robot.getVoxels().getW() - 1, robot.getVoxels().getH() / 2).getCenter().y;
      ys.add(y - y0);
      realTs.add((double) stopwatch.elapsed(TimeUnit.MICROSECONDS) / 1000000d);
      simTs.add(t);
    }
    stopwatch.stop();
    //compute things
    int dampingIndex = ys.size() - 2;
    while (dampingIndex > 0) {
      if (Math.abs(ys.get(dampingIndex) - ys.get(dampingIndex + 1)) > epsilon) {
        break;
      }
      dampingIndex--;
    }
    double elapsedSeconds = (double) stopwatch.elapsed(TimeUnit.MICROSECONDS) / 1000000d;
    //fill
    Map<String, List<Double>> timeEvolution = new LinkedHashMap<>();
    timeEvolution.put("st", simTs);
    timeEvolution.put("rt", realTs);
    timeEvolution.put("y", ys);
    List<Point2> finalTopPositions = new ArrayList<>();
    for (int x = 0; x < robot.getVoxels().getW(); x++) {
      Vector2 center = robot.getVoxels().get(x, 0).getCenter();
      finalTopPositions.add(Point2.build(center.x, center.y - y0));
    }
    return new Result(
        elapsedSeconds,
        realTs.get(dampingIndex),
        simTs.get(dampingIndex),
        realTs.size(),
        dampingIndex,
        (double) robot.getVoxels().count(v -> v != null) * (double) dampingIndex / realTs.get(dampingIndex),
        (double) robot.getVoxels().count(v -> v != null) * simTs.get(dampingIndex) / realTs.get(dampingIndex),
        (double) dampingIndex / realTs.get(dampingIndex),
        (double) robot.getVoxels().count(v -> v != null) * (double) realTs.size() / elapsedSeconds,
        (double) robot.getVoxels().count(v -> v != null) * simTs.get(simTs.size() - 1) / elapsedSeconds,
        (double) realTs.size() / elapsedSeconds,
        finalTopPositions.get(finalTopPositions.size() - 1).y,
        timeEvolution,
        finalTopPositions
    );
  }

  public static void main(String[] args) throws FileNotFoundException {
    ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    PrintStream timeEvolutionPS = null; //new PrintStream("/home/eric/experiments/2dhmsr/cantilever-time-evolutions.csv");
    List<Grid<Boolean>> shapes = Lists.newArrayList(
            Grid.create(15, 4),
            Grid.create(10, 4),
            Grid.create(20, 4)
    );
    Map<String, List<Object>> params = new LinkedHashMap<>();
    //params.put("settings.stepFrequency", Lists.newArrayList(1d/60d, 1d/120d, 1d/90d, 1d/45d, 1d/30d));
    //params.put("settings.positionConstraintSolverIterations", Lists.newArrayList(10, 4, 6, 8, 12, 15));
    //params.put("settings.velocityConstraintSolverIterations", Lists.newArrayList(10, 4, 6, 8, 12, 15));
    //params.put("builder.massLinearDamping", Lists.newArrayList(0.5, 0.01, 0.25, 0.75, 0.95));
    //params.put("builder.massAngularDamping", Lists.newArrayList(0.5, 0.01, 0.25, 0.75, 0.95));
    params.put("builder.springF", Lists.newArrayList(8, 4, 10, 15, 20, 25, 30));
    //params.put("builder.springD", Lists.newArrayList(1, 0.1, 0.25, 0.5, 0.75));
    //params.put("builder.massSideLengthRatio", Lists.newArrayList(.35, .1, .15, .25, .4));
    //params.put("builder.massCollisionFlag", Lists.newArrayList(false, true));
    //params.put("builder.limitContractionFlag", Lists.newArrayList(true, false));
    params.put("builder.springScaffoldings", Lists.newArrayList(
            EnumSet.of(Voxel.SpringScaffolding.SIDE_EXTERNAL, Voxel.SpringScaffolding.SIDE_INTERNAL, Voxel.SpringScaffolding.SIDE_CROSS, Voxel.SpringScaffolding.CENTRAL_CROSS),
            EnumSet.of(Voxel.SpringScaffolding.SIDE_EXTERNAL, Voxel.SpringScaffolding.SIDE_INTERNAL, Voxel.SpringScaffolding.CENTRAL_CROSS),
            EnumSet.of(Voxel.SpringScaffolding.SIDE_EXTERNAL, Voxel.SpringScaffolding.SIDE_INTERNAL, Voxel.SpringScaffolding.SIDE_CROSS),
            EnumSet.of(Voxel.SpringScaffolding.SIDE_EXTERNAL, Voxel.SpringScaffolding.CENTRAL_CROSS)
    ));
    Map<String, List> timeEvolutionMap = new HashMap<>();
    List<Future<Map<String, Object>>> futures = new ArrayList<>();
    for (Grid<Boolean> shape : shapes) {
      for (Map.Entry<String, List<Object>> param : params.entrySet()) {
        for (Object paramValue : param.getValue()) {
          //build basic settings and builder
          final Map<String, Object> configurations = new HashMap<>();
          configurations.put("settings", new Settings());
          //configurations.put("builder", Voxel.Builder.create()); //TODO fix with configuration
          //set all properties to the first value in the list
          for (Map.Entry<String, Object> configuration : configurations.entrySet()) {
            params.entrySet().stream().filter(e -> e.getKey().startsWith(configuration.getKey() + ".")).forEach((Map.Entry<String, List<Object>> e) -> {
              try {
                PropertyUtils.setProperty(
                        configuration.getValue(),
                        e.getKey().replace(configuration.getKey() + ".", ""),
                        e.getValue().get(0)
                );
              } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException ex) {
                System.out.printf("Cannot set property '%s' of '%s' due to: %s%n", e.getKey(), configuration.getKey(), ex);
              }
            });
          }
          //set param value
          try {
            PropertyUtils.setProperty(
                    configurations.get(param.getKey().split("\\.")[0]),
                    param.getKey().split("\\.")[1],
                    paramValue
            );
          } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException ex) {
            System.out.printf("Cannot set property '%s' to %s due to: %s%n", param.getKey(), paramValue, ex);
          }
          //set static keys
          final Map<String, Object> staticKeys = new LinkedHashMap<>();
          staticKeys.put("shape", shape.getW() + "x" + shape.getH());
          //set static keys to the first value in the list
          staticKeys.putAll(params.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get(0))));
          //set static key of the current param
          staticKeys.put(param.getKey(), paramValue);
          //submit jobs
          futures.add(executor.submit(() -> {
            System.out.printf("Started\t%s%n", staticKeys);
            CantileverBending cb = new CantileverBending(
                30d,
                0.1d,
                30d,
                0.01d,
                (Settings) configurations.get("settings")
            );
            Result result = cb.apply(Grid.create(shape.getW(), shape.getH(), (Voxel.Description) configurations.get("builder")));
            System.out.printf("Ended\t%s%n", staticKeys);
            Map<String, Object> row = new LinkedHashMap<>();
            row.putAll(staticKeys);
            if (timeEvolutionPS != null) {
              int length = result.getTimeEvolution().values().stream().mapToInt(List::size).max().orElse(0);
              Map<String, List> enhancedTimeEvolution = new LinkedHashMap<>(result.getTimeEvolution());
              enhancedTimeEvolution.putAll(staticKeys.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, o -> Collections.nCopies(length, o.getValue()))));
              synchronized (timeEvolutionMap) {
                for (Map.Entry<String, List> entry : enhancedTimeEvolution.entrySet()) {
                  List values = timeEvolutionMap.get(entry.getKey());
                  if (values == null) {
                    values = new ArrayList();
                    timeEvolutionMap.put(entry.getKey(), values);
                  }
                  values.addAll(entry.getValue());
                }
              }
            }
            row.putAll(PropertyUtils.describe(result)
                    .entrySet()
                    .stream()
                    .filter(e -> e.getValue() instanceof Number)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
            );
            return row;
          }));
        }
      }
    }
    //get results
    List<Map<String, Object>> rows = futures.stream().map(f -> {
      try {
        return f.get();
      } catch (InterruptedException | ExecutionException ex) {
        System.out.printf("Cannot get result due to: %s%n", ex);
      }
      return null;
    }).collect(Collectors.toList());
    executor.shutdown();
    //write table and finish
    try {
      CSVPrinter printer = new CSVPrinter(System.out, CSVFormat.DEFAULT.withHeader(rows.get(0).keySet().toArray(new String[0])));
      for (Map<String, Object> row : rows) {
        printer.printRecord(row.values().toArray());
      }
      printer.flush();
      printer.close();
    } catch (IOException ex) {
      Logger.getLogger(RobotControl.class.getName()).log(Level.SEVERE, "Cannot print CSV", ex);
    }
    //write time evolutions
    if (timeEvolutionPS != null) {
      printCSV(timeEvolutionMap, timeEvolutionPS);
    }
  }

  private static void printCSV(Map<String, List> data, PrintStream ps) {
    int length = data.values().stream().mapToInt(List::size).max().orElse(0);
    String[] names = data.keySet().toArray(new String[data.keySet().size()]);
    try {
      CSVPrinter printer = new CSVPrinter(ps, CSVFormat.DEFAULT.withHeader(names));
      for (int i = 0; i < length; i++) {
        Object[] values = new Object[names.length];
        for (int j = 0; j < names.length; j++) {
          List<?> currentValues = data.get(names[j]);
          if (currentValues.size() > i) {
            values[j] = currentValues.get(i);
          }
        }
        printer.printRecord(values);
      }
    } catch (IOException ex) {
      Logger.getLogger(CantileverBending.class.getName()).log(Level.SEVERE, "Cannot print CSV", ex);
    }
  }

}
