package com.watchspaces.repo;

import com.watchspaces.entity.Interaction;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InteractionRepository extends JpaRepository<Interaction, Long> {
    List<Interaction> findByUser_IdOrderByCreatedAtDescIdDesc(Long userId, Pageable page);
}
