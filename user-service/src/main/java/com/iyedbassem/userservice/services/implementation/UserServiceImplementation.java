package com.iyedbassem.userservice.services.implementation;

import com.iyedbassem.userservice.dtos.AddressDTO;
import com.iyedbassem.userservice.dtos.UserRequest;
import com.iyedbassem.userservice.dtos.UserResponse;
import com.iyedbassem.userservice.entities.Address;
import com.iyedbassem.userservice.entities.User;
import com.iyedbassem.userservice.repositories.UserRepository;
import com.iyedbassem.userservice.services.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserServiceImplementation implements UserService {
    private final UserRepository userRepo;

    @Override
    public List<UserResponse> fetchAllUsers() {
        return userRepo.findAll().stream()
                .map(this::mapUserToUserResponse)
                .toList();
    }

    @Override
    public Optional<UserResponse> getUserbyID(long id) {
        return userRepo.findById(id).map(this::mapUserToUserResponse);
    }

    @Override
    public void addUser(UserRequest userRequest) {
        User user = new User();
        updateUserFromRequest(user, userRequest);
        userRepo.save(user);
    }

    @Override
    public boolean updateUser(Long id, UserRequest updatedUser) {
        return userRepo.findById(id)
                .map(existingUser -> {
                    updateUserFromRequest(existingUser, updatedUser);
                    userRepo.save(existingUser);
                    return true;
                }).orElse(false);
    }

    @Override
    public boolean deleteUser(Long id) {
        return userRepo.findById(id)
                .map(existingUser -> {
                    userRepo.deleteById(existingUser.getId());
                    return true;
                }).orElse(false);
    }

    private void updateUserFromRequest(User user, UserRequest userRequest) {
        user.setFirstName(userRequest.getFirstName());
        user.setLastName(userRequest.getLastName());
        user.setEmail(userRequest.getEmail());
        user.setPhone(userRequest.getPhone());
        if (userRequest.getAddress() != null) {
            Address address = new Address();
            address.setCity(userRequest.getAddress().getCity());
            address.setStreet(userRequest.getAddress().getStreet());
            address.setState(userRequest.getAddress().getState());
            address.setCountry(userRequest.getAddress().getCountry());
            address.setZipCode(userRequest.getAddress().getZipCode());
            user.setAddress(address);
        }
    }

    private UserResponse mapUserToUserResponse(User user) {
        UserResponse userResponse = new UserResponse();
        userResponse.setId(String.valueOf(user.getId()));
        userResponse.setFirstName(user.getFirstName());
        userResponse.setLastName(user.getLastName());
        userResponse.setPhone(user.getPhone());
        userResponse.setEmail(user.getEmail());
        if (user.getAddress() != null) {
            AddressDTO addressDTO = new AddressDTO();
            addressDTO.setCity(user.getAddress().getCity());
            addressDTO.setState(user.getAddress().getState());
            addressDTO.setStreet(user.getAddress().getStreet());
            addressDTO.setZipCode(user.getAddress().getZipCode());
            addressDTO.setCountry(user.getAddress().getCountry());
            userResponse.setAddress(addressDTO);
        }
        return userResponse;
    }
}
