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
import it.units.erallab.hmsrobots.controllers.TimeFunctions;
import it.units.erallab.hmsrobots.objects.Ground;
import it.units.erallab.hmsrobots.objects.Robot;
import it.units.erallab.hmsrobots.objects.Voxel;
import it.units.erallab.hmsrobots.objects.WorldObject;
import it.units.erallab.hmsrobots.objects.immutable.BoundingBox;
import it.units.erallab.hmsrobots.objects.immutable.Snapshot;
import it.units.erallab.hmsrobots.tasks.AbstractTask;
import it.units.erallab.hmsrobots.util.Grid;
import it.units.erallab.hmsrobots.util.SerializableFunction;
import it.units.erallab.hmsrobots.viewers.SnapshotListener;
import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.dyn4j.dynamics.Settings;
import org.dyn4j.dynamics.World;
import org.dyn4j.geometry.Vector2;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * @author Eric Medvet <eric.medvet@gmail.com>
 */
public class RobotControl extends AbstractTask<Grid<Voxel.Description>, RobotControl.Result> {

  public static class Result {

    private final double realTime;
    private final long steps;
    private final double overallVoxelStepsPerSecond;
    private final double overallVoxelSimSecondsPerSecond;
    private final double overallStepsPerSecond;
    private final double avgBrokenRatio;
    private final double maxVelocityMagnitude;

    public Result(double realTime, long steps, double overallVoxelStepsPerSecond, double overallVoxelSimSecondsPerSecond, double overallStepsPerSecond, double avgBrokenRatio, double maxVelocityMagnitude) {
      this.realTime = realTime;
      this.steps = steps;
      this.overallVoxelStepsPerSecond = overallVoxelStepsPerSecond;
      this.overallVoxelSimSecondsPerSecond = overallVoxelSimSecondsPerSecond;
      this.overallStepsPerSecond = overallStepsPerSecond;
      this.avgBrokenRatio = avgBrokenRatio;
      this.maxVelocityMagnitude = maxVelocityMagnitude;
    }

    public double getRealTime() {
      return realTime;
    }

    public long getSteps() {
      return steps;
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

    public double getAvgBrokenRatio() {
      return avgBrokenRatio;
    }

    public double getMaxVelocityMagnitude() {
      return maxVelocityMagnitude;
    }

  }

  private final static int GROUND_HILLS_N = 100;
  private final static double GROUND_LENGTH = 1000d;
  private final static double INITIAL_PLACEMENT_X_GAP = 1d;
  private final static double INITIAL_PLACEMENT_Y_GAP = 1d;

  private final double finalT;
  private final double groundHillsHeight;
  private final double freq;

  public RobotControl(double finalT, double groundHillsHeight, double freq, Settings settings) {
    super(settings);
    this.finalT = finalT;
    this.groundHillsHeight = groundHillsHeight;
    this.freq = freq;
  }

  @Override
  public Result apply(Grid<Voxel.Description> voxelDescriptionGrid, SnapshotListener listener) {
    List<WorldObject> worldObjects = new ArrayList<>();
    //build voxel compound
    Grid<SerializableFunction<Double, Double>> functionGrid = Grid.create(voxelDescriptionGrid);
    for (Grid.Entry<Voxel.Description> entry : voxelDescriptionGrid) {
      functionGrid.set(entry.getX(), entry.getY(), t -> Math.sin(-2d * Math.PI * t * freq + 2d * Math.PI * (double) entry.getX() / (double) voxelDescriptionGrid.getW()));
    }
    Robot robot = new Robot(0, 0, new Robot.Description(
        voxelDescriptionGrid,
        new TimeFunctions(functionGrid)
    ));
    worldObjects.add(robot);
    //build ground
    Random random = new Random(1);
    double[] groundXs = new double[GROUND_HILLS_N + 2];
    double[] groundYs = new double[GROUND_HILLS_N + 2];
    for (int i = 1; i < groundXs.length - 1; i++) {
      groundXs[i] = 1d + GROUND_LENGTH / (double) (GROUND_HILLS_N) * (double) (i - 1);
      groundYs[i] = random.nextDouble() * groundHillsHeight;
    }
    groundXs[0] = 0d;
    groundXs[GROUND_HILLS_N + 1] = GROUND_LENGTH;
    groundYs[0] = GROUND_LENGTH / 10d;
    groundYs[GROUND_HILLS_N + 1] = GROUND_LENGTH / 10d;
    Ground ground = new Ground(groundXs, groundYs);
    worldObjects.add(ground);
    double[][] groundProfile = new double[][]{groundXs, groundYs};
    //position robot: x of rightmost point is on 2nd point of profile
    BoundingBox boundingBox = robot.boundingBox();
    double xLeft = groundProfile[0][1] + INITIAL_PLACEMENT_X_GAP;
    double yGroundLeft = groundProfile[1][1];
    double xRight = xLeft + boundingBox.max.x - boundingBox.min.x;
    double yGroundRight = yGroundLeft + (groundProfile[1][2] - yGroundLeft) * (xRight - xLeft) / (groundProfile[0][2] - xLeft);
    double topmostGroundY = Math.max(yGroundLeft, yGroundRight);
    Vector2 targetPoint = new Vector2(xLeft, topmostGroundY + INITIAL_PLACEMENT_Y_GAP);
    Vector2 currentPoint = new Vector2(boundingBox.min.x, boundingBox.min.y);
    Vector2 movement = targetPoint.subtract(currentPoint);
    robot.translate(movement);
    //build world w/o gravity
    World world = new World();
    world.setSettings(settings);
    for (WorldObject worldObject : worldObjects) {
      worldObject.addTo(world);
    }
    //prepare data
    double maxVelocityMagnitude = Double.NEGATIVE_INFINITY;
    double sumOfBrokenRatio = 0d;
    //simulate
    Stopwatch stopwatch = Stopwatch.createStarted();
    double t = 0d;
    long steps = 0;
    while (t < finalT) {
      //do step
      t = t + settings.getStepFrequency();
      world.step(1);
      steps = steps + 1;
      //control
      robot.act(t);
      if (listener != null) {
        Snapshot snapshot = new Snapshot(t, worldObjects.stream().map(WorldObject::immutable).collect(Collectors.toList()));
        listener.listen(snapshot);
      }
      //collect data
      for (Grid.Entry<Voxel> entry : robot.getVoxels()) {
        if (entry.getValue() != null) {
          double velocityMagnitude = 0d; //TODO fix me! entry.getValue().getSensorReading(Voxel.Sensor.VELOCITY_MAGNITUDE);
          double brokenRatio = 0d; //TODO fix me! entry.getValue().getSensorReading(Voxel.Sensor.BROKEN_RATIO);
          sumOfBrokenRatio = sumOfBrokenRatio + brokenRatio;
          maxVelocityMagnitude = Math.max(maxVelocityMagnitude, velocityMagnitude);
        }
      }
    }
    stopwatch.stop();
    double elapsedSeconds = (double) stopwatch.elapsed(TimeUnit.MICROSECONDS) / 1000000d;
    return new Result(
        elapsedSeconds, steps,
        (double) robot.getVoxels().count(v -> v != null) * (double) steps / elapsedSeconds,
        (double) robot.getVoxels().count(v -> v != null) * finalT / elapsedSeconds,
        (double) steps / elapsedSeconds,
        sumOfBrokenRatio / (double) robot.getVoxels().count(v -> v != null) / (double) steps,
        maxVelocityMagnitude
    );
  }

  public static void main(String[] args) {
    //TODO fix for using description
    ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    List<Grid<Boolean>> shapes = new ArrayList<>();
    int iterations = 5;
    for (int w = 15; w >= 3; w--) {
      shapes.add(Grid.create(w, 3, true));
    }
    Map<String, List<Object>> params = new LinkedHashMap<>();
    params.put("settings.stepFrequency", Lists.newArrayList(0.015, 0.005, 0.01, 0.02, 0.025));
    //params.put("settings.positionConstraintSolverIterations", Lists.newArrayList(10, 4, 6, 8, 12, 15));
    //params.put("settings.velocityConstraintSolverIterations", Lists.newArrayList(10, 4, 6, 8, 12, 15));
    //params.put("builder.massLinearDamping", Lists.newArrayList(0.5, 0.01, 0.25, 0.75, 0.95));
    //params.put("builder.massAngularDamping", Lists.newArrayList(0.5, 0.01, 0.25, 0.75, 0.95));
    //params.put("builder.springF", Lists.newArrayList(25, 5, 15, 30, 40));
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
    List<Future<Map<String, Object>>> futures = new ArrayList<>();
    for (Grid<Boolean> shape : shapes) {
      for (Map.Entry<String, List<Object>> param : params.entrySet()) {
        for (Object paramValue : param.getValue()) {
          for (int iteration = 0; iteration < iterations; iteration++) {
            //build basic settings and builder
            final Map<String, Object> configurations = new HashMap<>();
            configurations.put("settings", new Settings());
            //configurations.put("builder", Voxel.Builder.create()); //TODO fix!
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
            staticKeys.put("iteration", iteration);
            staticKeys.put("shape", shape.getW() + "x" + shape.getH());
            staticKeys.put("nVoxels", shape.values().stream().filter(b -> b).count());
            //set static keys to the first value in the list
            staticKeys.putAll(params.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get(0))));
            //set static key of the current param
            staticKeys.put(param.getKey(), paramValue);
            //submit jobs
            futures.add(executor.submit(() -> {
              System.out.printf("Started\t%s%n", staticKeys);
              RobotControl vcc = new RobotControl(50d, 5d, 1, (Settings) configurations.get("settings"));
              Result result = vcc.apply(Grid.create(shape.getW(), shape.getH(), (Voxel.Description) configurations.get("builder")));
              System.out.printf("Ended\t%s%n", staticKeys);
              Map<String, Object> row = new LinkedHashMap<>();
              row.putAll(staticKeys);
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
  }

}
