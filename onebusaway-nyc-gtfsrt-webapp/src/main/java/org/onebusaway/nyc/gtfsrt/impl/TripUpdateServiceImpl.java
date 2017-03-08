package org.onebusaway.nyc.gtfsrt.impl;

import com.google.transit.realtime.GtfsRealtime;
import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import org.onebusaway.nyc.gtfsrt.service.TripUpdateFeedBuilder;
import org.onebusaway.nyc.transit_data.services.NycTransitDataService;
import org.onebusaway.realtime.api.TimepointPredictionRecord;
import org.onebusaway.transit_data.model.ListBean;
import org.onebusaway.transit_data.model.VehicleStatusBean;
import org.onebusaway.transit_data.model.blocks.BlockInstanceBean;
import org.onebusaway.transit_data.model.blocks.BlockTripBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TripUpdateServiceImpl extends AbstractFeedMessageService {

    private TripUpdateFeedBuilder _feedBuilder;
    private NycTransitDataService _transitDataService;

    private static final Logger _log = LoggerFactory.getLogger(TripUpdateServiceImpl.class);

    @Autowired
    public void setFeedBuilder(TripUpdateFeedBuilder feedBuilder) {
        _feedBuilder = feedBuilder;
    }

    @Autowired
    public void setTransitDataService(NycTransitDataService transitDataService) {
        _transitDataService = transitDataService;
    }

    @Override
    protected List<FeedEntity.Builder> getEntities() {
        long time = getTime();
        ListBean<VehicleStatusBean> vehicles = _transitDataService.getAllVehiclesForAgency(getAgencyId(), time);

        List<FeedEntity.Builder> entities = new ArrayList<FeedEntity.Builder>();

        for (VehicleStatusBean vehicle : vehicles.getList()) {

            if (vehicle.getTrip() == null)
                continue;

            int tripSequence = vehicle.getTripStatus().getBlockTripSequence();
            BlockInstanceBean block =  _transitDataService.getBlockInstance(vehicle.getTrip().getBlockId(), vehicle.getTripStatus().getServiceDate());
            List<BlockTripBean> trips = block.getBlockConfiguration().getTrips();

            for (int i = tripSequence; i < trips.size(); i++) {
                String tripId = trips.get(i).getTrip().getId();
                List<TimepointPredictionRecord> tprs = _transitDataService.getPredictionRecordsForVehicleAndTrip(vehicle.getVehicleId(), tripId);
                if (tprs == null)
                    break;

                GtfsRealtime.TripUpdate tu  = _feedBuilder.makeTripUpdate(vehicle, tprs);

                FeedEntity.Builder entity = FeedEntity.newBuilder();
                entity.setTripUpdate(tu);
                entity.setId(tu.getTrip().getTripId());
                entities.add(entity);
            }

        }

        return entities;
    }

}
