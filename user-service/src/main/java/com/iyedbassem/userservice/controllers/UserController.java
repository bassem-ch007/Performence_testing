package com.iyedbassem.userservice.controllers;

import com.iyedbassem.userservice.dtos.UserRequest;
import com.iyedbassem.userservice.dtos.UserResponse;
import com.iyedbassem.userservice.services.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService userSer;

    @GetMapping
    public ResponseEntity<List<UserResponse>> getAll() {
        return new ResponseEntity<>(userSer.fetchAllUsers(), HttpStatus.OK);
    }

    @PostMapping
    public ResponseEntity<String> addUser(@RequestBody UserRequest user) {
        userSer.addUser(user);
        return new ResponseEntity<>("User Added Successfully", HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUser(@PathVariable Long id) {
        return userSer.getUserbyID(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<String> updateUser(@RequestBody UserRequest user, @PathVariable Long id) {
        boolean updateUser = userSer.updateUser(id, user);
        if (updateUser) {
            return new ResponseEntity<>("user updated successfully", HttpStatus.OK);
        }
        return new ResponseEntity<>("user is not updated", HttpStatus.NOT_FOUND);
    }
}
