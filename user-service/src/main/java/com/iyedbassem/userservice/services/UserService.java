package com.iyedbassem.userservice.services;

import com.iyedbassem.userservice.dtos.UserRequest;
import com.iyedbassem.userservice.dtos.UserResponse;

import java.util.List;
import java.util.Optional;

public interface UserService {
    List<UserResponse> fetchAllUsers();
    Optional<UserResponse> getUserbyID(long id);
    void addUser(UserRequest userRequest);
    boolean updateUser(Long id, UserRequest updatedUser);
    boolean deleteUser(Long id);
}
