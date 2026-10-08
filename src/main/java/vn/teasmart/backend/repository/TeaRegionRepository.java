package vn.teasmart.backend.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.TeaRegion;

public interface TeaRegionRepository extends JpaRepository<TeaRegion, Long> {
    boolean existsByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCaseAndRegionIdNot(String name, Long regionId);

    List<TeaRegion> findByStatusOrderByNameAsc(String status);
}
