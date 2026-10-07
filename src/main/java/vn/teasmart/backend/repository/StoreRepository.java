package vn.teasmart.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.teasmart.backend.entity.Store;

public interface StoreRepository extends JpaRepository<Store, Long> {
}
