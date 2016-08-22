/*
 * Copyright (C) 2011-2016 Rinde van Lon, iMinds-DistriNet, KU Leuven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rinde.gpem17;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Arrays.asList;

import java.io.File;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.github.rinde.ecj.BaseEvaluator;
import com.github.rinde.ecj.GPBaseNode;
import com.github.rinde.ecj.GPComputationResult;
import com.github.rinde.ecj.GPProgram;
import com.github.rinde.ecj.GPProgramParser;
import com.github.rinde.ecj.PriorityHeuristic;
import com.github.rinde.evo4mas.common.EvoBidder;
import com.github.rinde.evo4mas.common.GlobalStateObjectFunctions.GpGlobal;
import com.github.rinde.logistics.pdptw.mas.TruckFactory.DefaultTruckFactory;
import com.github.rinde.logistics.pdptw.mas.comm.AuctionCommModel;
import com.github.rinde.logistics.pdptw.mas.comm.AuctionPanel;
import com.github.rinde.logistics.pdptw.mas.comm.AuctionStopConditions;
import com.github.rinde.logistics.pdptw.mas.comm.Communicator;
import com.github.rinde.logistics.pdptw.mas.comm.DoubleBid;
import com.github.rinde.logistics.pdptw.mas.route.RoutePlanner;
import com.github.rinde.logistics.pdptw.mas.route.RtSolverRoutePlanner;
import com.github.rinde.logistics.pdptw.solver.CheapestInsertionHeuristic;
import com.github.rinde.rinsim.central.SolverModel;
import com.github.rinde.rinsim.central.rt.RtSolverModel;
import com.github.rinde.rinsim.central.rt.RtStAdapters;
import com.github.rinde.rinsim.core.Simulator;
import com.github.rinde.rinsim.core.model.time.RealtimeClockLogger;
import com.github.rinde.rinsim.core.model.time.TimeModel;
import com.github.rinde.rinsim.experiment.Experiment;
import com.github.rinde.rinsim.experiment.Experiment.SimArgs;
import com.github.rinde.rinsim.experiment.Experiment.SimulationResult;
import com.github.rinde.rinsim.experiment.ExperimentResults;
import com.github.rinde.rinsim.experiment.MASConfiguration;
import com.github.rinde.rinsim.experiment.PostProcessor;
import com.github.rinde.rinsim.io.FileProvider;
import com.github.rinde.rinsim.pdptw.common.AddVehicleEvent;
import com.github.rinde.rinsim.pdptw.common.RouteFollowingVehicle;
import com.github.rinde.rinsim.pdptw.common.RoutePanel;
import com.github.rinde.rinsim.pdptw.common.RouteRenderer;
import com.github.rinde.rinsim.pdptw.common.StatisticsDTO;
import com.github.rinde.rinsim.pdptw.common.StatsTracker;
import com.github.rinde.rinsim.pdptw.common.TimeLinePanel;
import com.github.rinde.rinsim.scenario.Scenario;
import com.github.rinde.rinsim.scenario.ScenarioIO;
import com.github.rinde.rinsim.scenario.StopConditions;
import com.github.rinde.rinsim.scenario.gendreau06.Gendreau06ObjectiveFunction;
import com.github.rinde.rinsim.ui.View;
import com.github.rinde.rinsim.ui.renderers.PDPModelRenderer;
import com.github.rinde.rinsim.ui.renderers.PlaneRoadModelRenderer;
import com.github.rinde.rinsim.ui.renderers.RoadUserRenderer;
import com.github.rinde.rinsim.util.StochasticSupplier;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.SetMultimap;

import ec.EvolutionState;
import ec.util.Parameter;

/**
 * 
 * @author Rinde van Lon
 */
public class Evaluator extends BaseEvaluator {

  enum Properties {
    DISTRIBUTED, NUM_SCENARIOS_PER_GEN;

    public String toString() {
      return name().toLowerCase();
    }
  }

  static final String TRAINSET_PATH = "files/train-dataset";
  static final long MAX_SIM_TIME = 8 * 60 * 60 * 1000L;

  static final Pattern CAPTURE_INSTANCE_ID =
    Pattern.compile(".*-(\\d+?)\\.scen");

  static final Gendreau06ObjectiveFunction OBJ_FUNC =
    Gendreau06ObjectiveFunction.instance(50d);

  final ImmutableList<Path> paths;
  boolean distributed;
  int numScenariosPerGen;

  public Evaluator() {
    super();

    List<Path> ps = new ArrayList<>(FileProvider.builder()
      .add(Paths.get(TRAINSET_PATH))
      .filter("regex:.*0\\.50-20-1\\.00-.*\\.scen")
      .build().get().asList());

    // sort on instance id
    Collections.sort(ps, new Comparator<Path>() {
      @Override
      public int compare(Path o1, Path o2) {
        Matcher m1 = CAPTURE_INSTANCE_ID.matcher(o1.getFileName().toString());
        Matcher m2 = CAPTURE_INSTANCE_ID.matcher(o2.getFileName().toString());
        checkArgument(m1.matches() && m2.matches());
        int id1 = Integer.parseInt(m1.group(1));
        int id2 = Integer.parseInt(m2.group(1));
        return Integer.compare(id1, id2);
      }
    });
    paths = ImmutableList.copyOf(ps);
  }

  @Override
  public void setup(final EvolutionState state, final Parameter base) {
    distributed =
      state.parameters.getBoolean(base.push(Properties.DISTRIBUTED.toString()),
        null, false);
    numScenariosPerGen =
      state.parameters.getInt(
        base.push(Properties.NUM_SCENARIOS_PER_GEN.toString()), null);
  }

  static Experiment.Builder experimentBuilder(boolean showGui,
      Iterable<Path> scenarioPaths, boolean distributed) {
    Experiment.Builder builder = Experiment.builder()
      .addScenarios(FileProvider.builder().add(scenarioPaths))
      .setScenarioReader(
        ScenarioIO.readerAdapter(Converter.INSTANCE))
      // .withThreads(1)
      .showGui(View.builder()
        .withAutoPlay()
        .withSpeedUp(64)
        .withAutoClose()
        .withResolution(1280, 768)
        .with(PlaneRoadModelRenderer.builder())
        .with(PDPModelRenderer.builder())
        .with(RoadUserRenderer.builder().withToStringLabel())
        .with(AuctionPanel.builder())
        .with(RoutePanel.builder())
        .with(RouteRenderer.builder())
        .with(TimeLinePanel.builder()))
      .showGui(showGui)
      .usePostProcessor(AuctionPostProcessor.INSTANCE);

    if (distributed) {
      builder.computeDistributed();
    }

    return builder;
  }

  static void evaluate(Iterable<GPProgram<GpGlobal>> progs,
      Iterable<Path> scenarioPaths, boolean distributed) {
    Experiment.Builder expBuilder =
      experimentBuilder(false, scenarioPaths, distributed);

    Map<MASConfiguration, String> map = new LinkedHashMap<>();
    for (GPProgram<GpGlobal> prog : progs) {
      MASConfiguration config = createStConfig(prog);
      map.put(config, prog.getId());
      expBuilder.addConfiguration(config);
    }
    ExperimentResults results = expBuilder.perform();
    File dest = new File("files/results/test.csv");

    StatsLogger.createHeader(dest);
    for (SimulationResult sr : results.sortedResults()) {
      StatsLogger.appendResults(asList(sr), dest,
        map.get(sr.getSimArgs().getMasConfig()));
    }
  }

  @Override
  public void evaluatePopulation(EvolutionState state) {
    SetMultimap<GPNodeHolder, IndividualHolder> mapping =
      getGPFitnessMapping(state);
    int fromIndex = state.generation * numScenariosPerGen;
    int toIndex = fromIndex + numScenariosPerGen;

    System.out.println(paths.subList(fromIndex, toIndex));
    Experiment.Builder expBuilder =
      experimentBuilder(false, paths.subList(fromIndex, toIndex), distributed);

    Map<MASConfiguration, GPNodeHolder> configGpMapping = new LinkedHashMap<>();
    for (GPNodeHolder node : mapping.keySet()) {

      // GPProgram<GpGlobal> prog =
      // GPProgramParser.parseProgramFunc("(insertioncost)",
      // (new FunctionSet()).create());

      final GPProgram<GpGlobal> prog = GPProgramParser
        .convertToGPProgram((GPBaseNode<GpGlobal>) node.trees[0].child);

      MASConfiguration config = createStConfig(prog);
      configGpMapping.put(config, node);
      expBuilder.addConfiguration(config);
    }

    ExperimentResults results = expBuilder.perform();
    List<GPComputationResult> convertedResults = new ArrayList<>();

    for (SimulationResult sr : results.getResults()) {
      StatisticsDTO stats = ((ResultObject) sr.getResultObject()).getStats();
      double cost = OBJ_FUNC.computeCost(stats);
      float fitness = (float) cost;
      if (!OBJ_FUNC.isValidResult(stats)) {
        fitness = Float.MAX_VALUE;
      }
      String id = configGpMapping.get(sr.getSimArgs().getMasConfig()).string;
      convertedResults.add(SingleResult.create((float) fitness, id, sr));
    }

    processResults(state, mapping, convertedResults);
  }

  @Override
  protected int expectedNumberOfResultsPerGPIndividual(EvolutionState state) {
    return numScenariosPerGen;
  }

  static MASConfiguration createStConfig(PriorityHeuristic<GpGlobal> solver) {
    StochasticSupplier<RoutePlanner> rp =
      RtSolverRoutePlanner.simulatedTimeSupplier(
        CheapestInsertionHeuristic.supplier(OBJ_FUNC));

    StochasticSupplier<? extends Communicator> cm =
      EvoBidder.simulatedTimeBuilder(solver, OBJ_FUNC)
        .withReauctionCooldownPeriod(60000);

    String name = "ReAuction-RP-EVO-BID-EVO-" + solver.getId();

    return createConfig(solver, rp, cm, false, name);
  }

  static MASConfiguration createRtConfig(PriorityHeuristic<GpGlobal> solver,
      String id) {
    StochasticSupplier<RoutePlanner> rp =
      RtSolverRoutePlanner.supplier(
        RtStAdapters.toRealtime(CheapestInsertionHeuristic.supplier(OBJ_FUNC)));

    StochasticSupplier<? extends Communicator> cm =
      EvoBidder.realtimeBuilder(solver, OBJ_FUNC)
        .withReauctionCooldownPeriod(60000);

    String name = "ReAuction-RP-EVO-BID-EVO-" + id;
    return createConfig(solver, rp, cm, true, name);
  }

  static MASConfiguration createConfig(PriorityHeuristic<GpGlobal> solver,
      StochasticSupplier<? extends RoutePlanner> rp,
      StochasticSupplier<? extends Communicator> cm,
      boolean rt,
      String name) {
    MASConfiguration.Builder builder = MASConfiguration.pdptwBuilder()
      .setName(name)
      .addEventHandler(AddVehicleEvent.class,
        DefaultTruckFactory.builder()
          .setRoutePlanner(rp)
          .setCommunicator(cm)
          .setLazyComputation(false)
          .setRouteAdjuster(RouteFollowingVehicle.delayAdjuster())
          .build())
      .addModel(AuctionCommModel.builder(DoubleBid.class)
        .withStopCondition(
          AuctionStopConditions.and(
            AuctionStopConditions.<DoubleBid>atLeastNumBids(2),
            AuctionStopConditions.<DoubleBid>or(
              AuctionStopConditions.<DoubleBid>allBidders(),
              AuctionStopConditions.<DoubleBid>maxAuctionDuration(5000))))
        .withMaxAuctionDuration(30 * 60 * 1000L));

    if (rt) {
      builder
        .addModel(RtSolverModel.builder()
          .withThreadPoolSize(3)
          .withThreadGrouping(true))
        .addModel(RealtimeClockLogger.builder());
    } else {
      builder.addModel(SolverModel.builder());
    }
    // .addEventHandler(AddParcelEvent.class, AddParcelEvent.namedHandler())
    return builder.build();
  }

  enum Converter implements Function<Scenario, Scenario> {
    INSTANCE {
      @Override
      public Scenario apply(Scenario input) {
        return Scenario.builder(input)
          .removeModelsOfType(TimeModel.AbstractBuilder.class)
          .addModel(TimeModel.builder().withTickLength(250))
          .setStopCondition(StopConditions.or(input.getStopCondition(),
            StopConditions.limitedTime(MAX_SIM_TIME),
            EvoStopCondition.INSTANCE))
          .build();
      }
    }
  }

  enum AuctionPostProcessor
    implements PostProcessor<ResultObject>,Serializable {
    INSTANCE {
      @Override
      public ResultObject collectResults(Simulator sim, SimArgs args) {
        @Nullable
        final AuctionCommModel<?> auctionModel =
          sim.getModelProvider().tryGetModel(AuctionCommModel.class);

        final Optional<AuctionStats> aStats;
        if (auctionModel == null) {
          aStats = Optional.absent();
        } else {
          final int parcels = auctionModel.getNumParcels();
          final int reauctions = auctionModel.getNumAuctions() - parcels;
          final int unsuccessful = auctionModel.getNumUnsuccesfulAuctions();
          final int failed = auctionModel.getNumFailedAuctions();
          aStats = Optional
            .of(AuctionStats.create(parcels, reauctions, unsuccessful, failed));
        }

        final StatisticsDTO stats =
          sim.getModelProvider().getModel(StatsTracker.class).getStatistics();
        return ResultObject.create(stats, aStats);
      }

      @Override
      public FailureStrategy handleFailure(Exception e, Simulator sim,
          SimArgs args) {
        return FailureStrategy.ABORT_EXPERIMENT_RUN;
      }
    }
  }
}
