package com.craboffice.backend.repository;

import com.craboffice.backend.entity.LocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LocationRepository extends JpaRepository<LocationEntity, Long> {
    List<LocationEntity> findByRoomIdOrderBySortOrderAsc(Long roomId);
}
