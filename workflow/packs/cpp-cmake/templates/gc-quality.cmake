option(ENABLE_WERROR "Treat warnings as errors for Ground Control gates" ON)
if(ENABLE_WERROR)
  add_compile_options(-Wall -Wextra -Wpedantic -Werror)
endif()
