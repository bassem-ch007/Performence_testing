package com.iyedbassem.ecommercemono.reposotories;

import com.iyedbassem.ecommercemono.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
}
