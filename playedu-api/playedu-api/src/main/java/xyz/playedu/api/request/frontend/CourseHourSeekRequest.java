/*
 * Copyright (C) 2023 閺夘厼绐為惂鎴掑姛缁夋垶濡ч張澶愭閸忣剙寰?
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package xyz.playedu.api.request.frontend;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CourseHourSeekRequest {
    @NotBlank(message = "token参数不存在")
    private String token;

    @NotNull(message = "from参数不存在")
    private Integer from;

    @NotNull(message = "to参数不存在")
    private Integer to;
}
