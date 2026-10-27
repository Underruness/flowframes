# Embeds the warp compute shaders as C arrays (ncnn compiles GLSL to SPIR-V at runtime).
set(RIFE_SHADER_HEADERS)
foreach(SHADER warp warp_pack4)
    set(SHADER_SRC ${CMAKE_CURRENT_LIST_DIR}/${SHADER}.comp)
    set(SHADER_COMP_HEADER ${CMAKE_CURRENT_BINARY_DIR}/${SHADER}.comp.hex.h)
    add_custom_command(
        OUTPUT ${SHADER_COMP_HEADER}
        COMMAND ${CMAKE_COMMAND} -DSHADER_SRC=${SHADER_SRC} -DSHADER_COMP_HEADER=${SHADER_COMP_HEADER}
                -P ${CMAKE_CURRENT_LIST_DIR}/generate_shader_comp_header.cmake
        DEPENDS ${SHADER_SRC}
        COMMENT "Embedding shader ${SHADER}.comp")
    list(APPEND RIFE_SHADER_HEADERS ${SHADER_COMP_HEADER})
endforeach()
