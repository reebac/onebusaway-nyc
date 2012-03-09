/**
 * Copyright (c) 2011 Metropolitan Transportation Authority
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.onebusaway.nyc.vehicle_tracking.impl.inference.rules;

import org.onebusaway.geospatial.model.CoordinatePoint;
import org.onebusaway.geospatial.services.SphericalGeometryLibrary;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.BlockStateService;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.BlockStateService.BestBlockStates;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.MissingShapePointsException;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.Observation;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.ObservationCache;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.ObservationCache.EObservationCacheKey;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.VehicleStateLibrary;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.BlockState;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.BlockStateObservation;
import org.onebusaway.nyc.vehicle_tracking.impl.inference.state.VehicleState;
import org.onebusaway.nyc.vehicle_tracking.impl.particlefilter.SensorModelResult;
import org.onebusaway.realtime.api.EVehiclePhase;
import org.onebusaway.transit_data_federation.model.ProjectedPoint;
import org.onebusaway.transit_data_federation.services.blocks.BlockInstance;
import org.onebusaway.transit_data_federation.services.transit_graph.BlockStopTimeEntry;
import org.onebusaway.transit_data_federation.services.transit_graph.StopTimeEntry;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.apache.commons.math.util.FastMath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import umontreal.iro.lecuyer.probdist.FoldedNormalDist;
import umontreal.iro.lecuyer.probdist.NormalDist;
import umontreal.iro.lecuyer.probdist.TruncatedDist;
import umontreal.iro.lecuyer.stat.Tally;

import java.util.Set;
import java.util.concurrent.TimeUnit;

//@Component
public class ScheduleLikelihood implements SensorModelRule {

  private BlockStateService _blockStateService;

  /*
   * These are all in minutes
   */
  final static public double schedDevMeanPrior = 15.0;
  final static public double schedDevStdDevPrior = 80.0;
  final static public double schedStdDevPrior = 100.0;
  final static public double schedTransStdDev = 1.0;
  final private double layoverDuringMean = 15.0;
  final private double layoverDuringStdDev = 80.0;
  final private double layoverBeforeMean = 15.0;
  final private double layoverBeforeStdDev = 80.0;

  @Autowired
  public void setBlockStateService(BlockStateService blockStateService) {
    _blockStateService = blockStateService;
  }

  @Override
  public SensorModelResult likelihood(SensorModelSupportLibrary library,
      Context context) {
    
    final VehicleState state = context.getState();
    final Observation obs = context.getObservation();
    final EVehiclePhase phase = state.getJourneyState().getPhase();
    final BlockState blockState = state.getBlockState();
    final VehicleState parentState = context.getParentState();
    final BlockStateObservation parentBlockState = 
        parentState != null ? parentState.getBlockStateObservation() : null;
    
    
    final SensorModelResult result;
    if (parentBlockState == null || blockState == null) {
      result = computeSchedDevPriorProb(state, parentState, blockState, phase, obs);
    } else {
      result = new SensorModelResult("pSchedule", 1.0);
      double obsTimeDiff = (obs.getTime() - obs.getPreviousObservation().getTime())/1000.0;
      final double pDevTrans = NormalDist.density(parentBlockState.getScheduleDeviation(), 
          obsTimeDiff/60.0 * schedTransStdDev/2, state.getBlockStateObservation().getScheduleDeviation());
      result.addResultAsAnd("in progress(dev-trans)", pDevTrans);
    }
    return result;
  }
  
  private SensorModelResult computeSchedDevPriorProb(VehicleState state, 
      VehicleState parentState, BlockState blockState, EVehiclePhase phase, Observation obs) {
    final SensorModelResult result = new SensorModelResult("pSchedule", 1.0);
    if (blockState == null) {
      /*
       * 50/50 on this one
       */
      final double pSched = FoldedNormalDist.density(schedDevMeanPrior, schedDevStdDevPrior, schedDevMeanPrior);
      result.addResultAsAnd("sched", pSched);
    } else if (EVehiclePhase.DEADHEAD_AFTER == phase) {
      
      final double pSched = FoldedNormalDist.density(schedDevMeanPrior, schedDevStdDevPrior, schedDevMeanPrior);
      result.addResultAsAnd("deadhead after", pSched);
      
    } else if (EVehiclePhase.DEADHEAD_BEFORE == phase) {
      
      final double pSched;
      if (Double.compare(blockState.getBlockLocation().getDistanceAlongBlock(), 0.0)
          == 0) {
        /*
         * Actually before the block...
         * measure how likely it is to start on time.
         */
        double startDev = SensorModelSupportLibrary.startOrResumeBlockOnTimeDev(state, obs);
        pSched = FoldedNormalDist.density(schedDevMeanPrior, schedDevStdDevPrior,
            startDev);
        
        result.addResultAsAnd("deadhead before(before block)", pSched);
      } else {
      
        /*
         * On the block, but still not started...
         * measure, inversely, how likely it is to be 
         * riding along the trip until it gets to where it
         * wants to go in-service.
         */
        pSched = FoldedNormalDist.density(schedDevMeanPrior, schedDevStdDevPrior,
            FastMath.abs(state.getBlockStateObservation().getScheduleDeviation()));
        result.addResultAsAnd("deadhead before(on block)", 1-pSched);
      }
      
    } else if (EVehiclePhase.DEADHEAD_DURING == phase) {
      
      double startDev = SensorModelSupportLibrary.startOrResumeBlockOnTimeDev(state, obs);
      final double pSched = FoldedNormalDist.density(schedDevMeanPrior, schedDevStdDevPrior,
          startDev);
      result.addResultAsAnd("deadhead during", pSched);
      
    } else if (EVehiclePhase.LAYOVER_DURING == phase) {
      
      final BlockStopTimeEntry nextStop = 
          VehicleStateLibrary.getPotentialLayoverSpot(blockState.getBlockLocation());

      final double pLayoverDuring = computeLayoverSchedDevProb(obs.getTime(), blockState,
          nextStop, true);
      result.addResultAsAnd("layover during", pLayoverDuring);
      
    } else if (EVehiclePhase.LAYOVER_BEFORE == phase) {
      final BlockStopTimeEntry nextStop = 
          VehicleStateLibrary.getPotentialLayoverSpot(blockState.getBlockLocation());

      final double pLayoverBefore = computeLayoverSchedDevProb(obs.getTime(), blockState,
          nextStop, false);
      result.addResultAsAnd("layover before", pLayoverBefore);
      
    } else if (EVehiclePhase.IN_PROGRESS == phase) {
      final double pSched = FoldedNormalDist.density(schedDevMeanPrior, schedDevStdDevPrior,
          FastMath.abs(state.getBlockStateObservation().getScheduleDeviation()));
      result.addResultAsAnd("in progress", pSched);
    }

    return result;
  }

  private double computeLayoverSchedDevProb(long timestamp,
      BlockState blockState, BlockStopTimeEntry layoverStop, boolean during) {

    final double mean = during?layoverDuringMean:layoverBeforeMean;
    final double stdDev = during?layoverDuringStdDev:layoverBeforeStdDev;
    
    final BlockInstance blockInstance = blockState.getBlockInstance();

    // No next stop? Could just be sitting...
    if (layoverStop == null) {
      final double pSched = FoldedNormalDist.density(mean, stdDev, mean);
      return 0.5*pSched;
    }

    final StopTimeEntry stopTime = layoverStop.getStopTime();
    final long arrivalTime = blockInstance.getServiceDate()
        + stopTime.getArrivalTime() * 1000;

    // If we still have time to spare, then no problem!
    final int minutesLate;
    if (timestamp <= arrivalTime)
      minutesLate = 0;
    else
      minutesLate = (int) ((timestamp - arrivalTime) / (60 * 1000));

    final double pSched = FoldedNormalDist.density(mean, stdDev, minutesLate);
    return pSched;
  }
  
}
