package org.framework.starter.dto;

import lombok.Data;

@Data
public class UserDto {

    private Integer id;
    private String login;
    private String name;
    private String company;
    private String location;
    private String email;

}
