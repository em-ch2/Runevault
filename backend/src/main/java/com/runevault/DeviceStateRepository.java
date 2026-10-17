package com.runevault;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface DeviceStateRepository extends JpaRepository<DeviceState, String> {
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select d from DeviceState d where d.deviceId = :id")
 Optional<DeviceState> findForUpdate(@Param("id") String id);
}
