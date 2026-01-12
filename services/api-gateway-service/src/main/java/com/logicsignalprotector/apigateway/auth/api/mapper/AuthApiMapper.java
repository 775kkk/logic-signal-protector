package com.logicsignalprotector.apigateway.auth.api.mapper;

import com.logicsignalprotector.apigateway.auth.api.dto.RegisterResponse;
import com.logicsignalprotector.apigateway.auth.domain.UserEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AuthApiMapper {
  RegisterResponse toRegisterResponse(UserEntity user);
}
