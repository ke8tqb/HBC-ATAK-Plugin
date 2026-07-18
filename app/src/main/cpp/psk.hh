/*
Phase-shift keying

Copyright 2018 Ahmet Inan <xdsopl@gmail.com>
*/

#pragma once

template <int BITS, typename TYPE, typename CODE>
struct PhaseShiftKeying;

template <typename TYPE, typename CODE>
struct PhaseShiftKeying<2, TYPE, CODE>
{
	static const int NUM = 2;
	static const int BITS = 1;
	typedef TYPE complex_type;
	typedef typename TYPE::value_type value_type;
	typedef CODE code_type;

	static constexpr value_type DIST = 2;

	static code_type quantize(value_type precision, value_type value)
	{
		value *= DIST * precision;
		if (std::is_integral<code_type>::value)
			value = std::nearbyint(value);
		if (std::is_same<code_type, int8_t>::value)
			value = std::min<value_type>(std::max<value_type>(value, -128), 127);
		return value;
	}

	static void hard(code_type *b, complex_type c)
	{
		b[0] = c.real() < value_type(0) ? code_type(-1) : code_type(1);
	}

	static void soft(code_type *b, complex_type c, value_type precision)
	{
		b[0] = quantize(precision, c.real());
	}

	static complex_type map(code_type *b)
	{
		return complex_type(b[0], 0);
	}
};

template <typename TYPE, typename CODE>
struct PhaseShiftKeying<4, TYPE, CODE>
{
	static const int NUM = 4;
	static const int BITS = 2;
	typedef TYPE complex_type;
	typedef typename TYPE::value_type value_type;
	typedef CODE code_type;

	// 1/sqrt(2)
	static constexpr value_type rcp_sqrt_2 = 0.70710678118654752440;
	static constexpr value_type DIST = 2 * rcp_sqrt_2;

	static code_type quantize(value_type precision, value_type value)
	{
		value *= DIST * precision;
		if (std::is_integral<code_type>::value)
			value = std::nearbyint(value);
		if (std::is_same<code_type, int8_t>::value)
			value = std::min<value_type>(std::max<value_type>(value, -128), 127);
		return value;
	}

	static void hard(code_type *b, complex_type c)
	{
		b[0] = c.real() < value_type(0) ? code_type(-1) : code_type(1);
		b[1] = c.imag() < value_type(0) ? code_type(-1) : code_type(1);
	}

	static void soft(code_type *b, complex_type c, value_type precision)
	{
		b[0] = quantize(precision, c.real());
		b[1] = quantize(precision, c.imag());
	}

	static complex_type map(code_type *b)
	{
		return rcp_sqrt_2 * complex_type(b[0], b[1]);
	}
};
