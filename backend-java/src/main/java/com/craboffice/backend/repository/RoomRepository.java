package com.craboffice.backend.repository;

import com.craboffice.backend.entity.RoomEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoomRepository extends JpaRepository<RoomEntity, Long> {

	@EntityGraph(attributePaths = "agents")
	Optional<RoomEntity> findWithAgentsById(Long id);

	@EntityGraph(attributePaths = {"agents", "locations"})
	Optional<RoomEntity> findWithAgentsAndLocationsById(Long id);
}
