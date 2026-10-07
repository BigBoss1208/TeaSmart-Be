package vn.teasmart.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.TeaRegion;

public interface TeaRegionRepository extends JpaRepository<TeaRegion, Long> {
}
