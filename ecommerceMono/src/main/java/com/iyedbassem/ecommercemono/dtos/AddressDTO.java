package com.iyedbassem.ecommercemono.dtos;

import lombok.Data;

@Data
public class AddressDTO {
    private String street;
    private String city;
    private String zipCode;
    private String state;
    private String country;
}