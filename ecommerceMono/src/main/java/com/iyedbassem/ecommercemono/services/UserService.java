package com.iyedbassem.ecommercemono.services;

import com.iyedbassem.ecommercemono.dtos.UserRequest;
import com.iyedbassem.ecommercemono.dtos.UserResponse;

import java.util.List;
import java.util.Optional;

public interface UserService {
    List<UserResponse> fetchAllUsers();
    Optional<UserResponse> getUserbyID(long id);
    void addUser(UserRequest userRequest);
    boolean updateUser(Long id, UserRequest updatedUser);
    boolean deleteUser(Long id );
}
