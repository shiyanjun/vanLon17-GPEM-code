/*
 * Copyright (C) 2015-2016 Rinde van Lon, iMinds-DistriNet, KU Leuven
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
package com.github.rinde.gpem17.eval;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.rinde.logistics.pdptw.mas.comm.AuctionCommModel.AuctionEvent;
import com.github.rinde.logistics.pdptw.mas.comm.Bidder;
import com.github.rinde.rinsim.central.SolverTimeMeasurement;
import com.github.rinde.rinsim.experiment.Experiment.SimArgs;
import com.github.rinde.rinsim.experiment.Experiment.SimulationResult;
import com.github.rinde.rinsim.scenario.Scenario;
import com.github.rinde.rinsim.scenario.gendreau06.Gendreau06ObjectiveFunction;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;

/**
 *
 * @author Rinde van Lon
 */
public class VanLonHolvoetResultWriter extends ResultWriter {
  final Map<File, Map<String, String>> scenarioPropsCache;
  final String dataset;
  boolean createTmpResultFiles;

  public VanLonHolvoetResultWriter(File target,
      Gendreau06ObjectiveFunction objFunc, String datasetPath, boolean rt,
      boolean createFinalFiles, boolean createTmpFiles,
      boolean minimizeIO) {
    super(target, objFunc, rt, createFinalFiles, minimizeIO);
    createTmpResultFiles = createTmpFiles;
    dataset = datasetPath;
    scenarioPropsCache = new LinkedHashMap<>();
  }

  @Override
  public void receive(SimulationResult result) {
    if (createTmpResultFiles) {
      final String configName = result.getSimArgs().getMasConfig().getName();
      final File targetFile =
        new File(experimentDirectory, configName + ".csv");

      if (!targetFile.exists()) {
        createCSVWithHeader(targetFile);
      }
      appendSimResult(result, targetFile);

      if (realtime) {
        writeTimeLog(result);
      }
      writeBidComputationTimeMeasurements(result);
    }
  }

  void writeBidComputationTimeMeasurements(SimulationResult result) {
    if (!(result.getResultObject() instanceof SimResult)) {
      return;
    }
    final SimResult info = (SimResult) result.getResultObject();

    if (!info.getAuctionEvents().isEmpty()
      && !info.getTimeMeasurements().isEmpty()) {

      final SimArgs simArgs = result.getSimArgs();
      final Scenario scenario = simArgs.getScenario();

      final String id = Joiner.on("-").join(
        simArgs.getMasConfig().getName(),
        scenario.getProblemClass().getId(),
        scenario.getProblemInstanceId(),
        simArgs.getRandomSeed(),
        simArgs.getRepetition());

      File statsDir = new File(experimentDirectory, "computation-time-stats");
      statsDir.mkdirs();

      final File auctionsFile = new File(statsDir, id + "-auctions.csv");
      final File compFile = new File(statsDir, id + "-bid-computations.csv");

      StringBuilder auctionContents = new StringBuilder();
      auctionContents.append("auction_start,auction_end,num_bids")
        .append(System.lineSeparator());
      for (AuctionEvent e : info.getAuctionEvents()) {
        Joiner.on(",").appendTo(auctionContents,
          e.getAuctionStartTime(),
          e.getTime(),
          e.getNumBids());
        auctionContents.append(System.lineSeparator());
      }

      ImmutableListMultimap<Bidder<?>, SolverTimeMeasurement> measurements =
        info.getTimeMeasurements();
      StringBuilder compContents = new StringBuilder();
      compContents
        .append("bidder_id,comp_start_sim_time,route_length,duration_ns")
        .append(System.lineSeparator());
      int bidderId = 0;
      for (Bidder<?> bidder : measurements.keySet()) {
        List<SolverTimeMeasurement> ms = measurements.get(bidder);
        for (SolverTimeMeasurement m : ms) {

          // int available = m.input().getAvailableParcels().size();
          // int total = GlobalStateObjects.allParcels(m.input()).size();
          // int pickedUp = total - available;
          // (available * 2) + pickedUp;
          int routeLength =
            m.input().getVehicles().get(0).getRoute().get().size();

          Joiner.on(",").appendTo(compContents,
            bidderId,
            m.input().getTime(),
            routeLength,
            m.durationNs());
          compContents.append(System.lineSeparator());
        }
        bidderId++;
      }
      try {
        Files.write(auctionContents, auctionsFile, Charsets.UTF_8);
        Files.write(compContents, compFile, Charsets.UTF_8);
      } catch (IOException e1) {
        throw new IllegalStateException(e1);
      }

    }
  }

  @Override
  void appendSimResult(SimulationResult sr, File destFile) {
    try {
      String line = appendTo(sr, new StringBuilder()).toString();
      Files.append(line, destFile, Charsets.UTF_8);
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
  }

  Map<String, String> getScenarioProps(File f) {
    if (scenarioPropsCache.containsKey(f)) {
      return scenarioPropsCache.get(f);
    }
    try {
      List<String> propsStrings = Files.readLines(f, Charsets.UTF_8);
      final Map<String, String> properties = Splitter.on("\n")
        .withKeyValueSeparator(" = ")
        .split(Joiner.on("\n").join(propsStrings));
      scenarioPropsCache.put(f, properties);
      return properties;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  StringBuilder appendTo(SimulationResult sr, StringBuilder sb) {
    final String pc = sr.getSimArgs().getScenario().getProblemClass().getId();
    final String id = sr.getSimArgs().getScenario().getProblemInstanceId();

    final String scenarioName = Joiner.on("-").join(pc, id);
    File scenFile = new File(new StringBuilder().append(dataset).append("/")
      .append(scenarioName).append(".properties").toString());
    final Map<String, String> properties = getScenarioProps(scenFile);

    final ImmutableMap.Builder<Enum<?>, Object> map =
      ImmutableMap.<Enum<?>, Object>builder()
        .put(OutputFields.SCENARIO_ID, scenarioName)
        .put(OutputFields.DYNAMISM, properties.get("dynamism_bin"))
        .put(OutputFields.URGENCY, properties.get("urgency"))
        .put(OutputFields.SCALE, properties.get("scale"))
        .put(OutputFields.NUM_ORDERS, properties.get("AddParcelEvent"))
        .put(OutputFields.NUM_VEHICLES, properties.get("AddVehicleEvent"))
        .put(OutputFields.RANDOM_SEED, sr.getSimArgs().getRandomSeed())
        .put(OutputFields.REPETITION, sr.getSimArgs().getRepetition());

    addSimOutputs(map, sr, objectiveFunction);

    return appendValuesTo(sb, map.build(), getFields())
      .append(System.lineSeparator());
  }

  @Override
  Iterable<Enum<?>> getFields() {
    return ImmutableList.<Enum<?>>copyOf(OutputFields.values());
  }

  static <T extends Enum<?>> StringBuilder appendValuesTo(StringBuilder sb,
      Map<T, Object> props, Iterable<T> keys) {
    final List<Object> values = new ArrayList<>();
    for (final T p : keys) {
      checkArgument(props.containsKey(p));
      values.add(props.get(p));
    }
    Joiner.on(",").appendTo(sb, values);
    return sb;
  }
}
