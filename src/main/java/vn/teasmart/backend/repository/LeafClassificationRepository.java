package vn.teasmart.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.LeafClassification;

public interface LeafClassificationRepository extends JpaRepository<LeafClassification, Long> {
}
