/**
 * This file was modified from `mrc.py`, which contained the following information:
 *     # Downloaded from http://ami.scripps.edu/software/tiltpicker/
 *     # tiltpicker/pyami/mrc.py
 *     # This file had no copyright notice, but another in the same directory had this:
 *     #
 *     #
 *     # COPYRIGHT:
 *     # The Leginon software is Copyright 2003
 *     # The Scripps Research Institute, La Jolla, CA
 *     # For terms of the license agreement
 *     # see	http://ami.scripps.edu/software/leginon-license
 *     
 * JavaScript translation created 12 January 2022.
 * @author Jonathan Piland (https://github.com/jpiland16)
 * 
 */

class Complex64Array {
    constructor () {
        console.error("Complex64Array not yet supported.")
    }
}

const mrc2arraytype = {
    0: Uint8Array,
    1: Int16Array,
    2: Float32Array,
    // 	3:  complex made of two int16.  No such thing in numpy
    //     however, we could manually build a complex array by reading two
    //     int16 arrays somehow.
    4: Complex64Array,
    6: Uint16Array,  // according to UCSF
    101: Uint8Array,  // s4-bit format support
}

const header_fields = [
    ["nx", "int32"],
    ["ny", "int32"],
    ["nz", "int32"],
    ["mode", "int32"],
    ["nxstart", "int32"],
    ["nystart", "int32"],
    ["nzstart", "int32"],
    ["mx", "int32"],
    ["my", "int32"],
    ["mz", "int32"],
    ["xlen", "float32"],
    ["ylen", "float32"],
    ["zlen", "float32"],
    ["alpha", "float32"],
    ["beta", "float32"],
    ["gamma", "float32"],
    ["mapc", "int32"],
    ["mapr", "int32"],
    ["maps", "int32"],
    ["amin", "float32"],
    ["amax", "float32"],
    ["amean", "float32"],
    ["ispg", "int32"],
    ["nsymbt", "int32"],
    ["extra", "string", 100],
    ["xorigin", "float32"],
    ["yorigin", "float32"],
    ["zorigin", "float32"],
    ["map", "string", 4],
    ["byteorder", "int32"],
    ["rms", "float32"],
    ["nlabels", "int32"],
    ["label0", "string", 80],
    ["label1", "string", 80],
    ["label2", "string", 80],
    ["label3", "string", 80],
    ["label4", "string", 80],
    ["label5", "string", 80],
    ["label6", "string", 80],
    ["label7", "string", 80],
    ["label8", "string", 80],
    ["label9", "string", 80],
]

/** 
* Detect byte order (endianness) of MRC file based on one or more tests on
* the header data.
* @param {Uint8Array} headerbytes
*/
function isSwapped(headerbytes) {

	const BIG_ENDIAN = 0x11110000;
	const LITTLE_ENDIAN = 0x44440000;

    // try to read the machine stamp
    let machstamp = headerbytes.slice(212, 216);
    if (machstamp[0] === LITTLE_ENDIAN) {
        return false;
    } else if (machstamp[0] === BIG_ENDIAN) {
		throw new Error("Big-endian MRC files aren't supported yet");
    }

	// check for valid mode, with or without byteswap
	let modeSlice = headerbytes.slice(12, 16)
	let mode = bytesToData(modeSlice, Uint32Array) // Count = 1
	if (mrc2arraytype.hasOwnProperty(mode[0]))
		return false
	else {
		mode = bytesToData(modeSlice, Uint32Array, true) // Count = 1
		if (mrc2arraytype.hasOwnProperty(mode[0])) {
			throw new Error("Big-endian MRC files aren't supported yet");
		}
	}

	// don't know: just pick the one order we actually support
	console.warn("Can't detect endianness for MRC file, assuming little-endian");
	return false;
}

/**
 * Parse the 1024 byte MRC header into a header dictionary.
 * @param {Array} headerbytes   
 */
function parseHeader(headerbytes) {
    // header is comprised of int32, float32, and text labels.
    const itype = Int32Array
    const ftype = Float32Array

    // check if data needs to be byte swapped
    let swapped = isSwapped(headerbytes)

    // Convert 1k header into both floats and ints to make it easy
    // to extract all the info.
    // Only convert first 224 bytes into numbers because the
    // remainder of data are text labels
    const headerarray = {}
    headerarray["float32"] = bytesToData(headerbytes, ftype, swapped) // Count = 224
    headerarray["int32"] = bytesToData(headerbytes, itype, swapped) // Count = 224

    // fill in header dictionary with all the info
    const newheader = {}
    let pos = 0

    for (const field of header_fields) {
        let fieldName = field[0]
        let type = field[1]
        let length;
        if (type == "string") {
            length = field[2]
            newheader[fieldName] = headerbytes.slice(pos, pos + length)
        } else {
            length = 4
            let word = Math.round(pos / 4)
            newheader[fieldName] = headerarray[type][word]
        }
        pos += length
    }

    // Save some numpy specific info (not directly related to MRC).
    // numpy dtype added to header dict because it includes both the
    // basic type (from MRC "mode") and also the byte order, which has
    // been determined independent from the byte order designation in the
    // header, which may be invalid.  This allows the data to be read
    // properly.  Also figure out the numpy shape of the data from dimensions.
    let dtype = mrc2arraytype[newheader["mode"]]
    let shape;
    newheader["dtype"] = dtype
    newheader["swapped"] = swapped
    if (newheader["nz"] > 1) // 3D data
        shape = [newheader["nz"], newheader["ny"], newheader["nx"]];
    else if (newheader["ny"] > 1) // 2D data
        shape = [newheader["ny"], newheader["nx"]];
    else // 1D data
        shape = [newheader["nx"]]
    newheader["shape"] = shape

    return newheader
}

/**
 * Process incoming MRC data and convert it to the appropriate type.
 * 
 * Note: Some issues with the test image prompted the possibility of using the 
 * header data as voxels itself. While this seems counterintuitive, I can prove
 * that the python script did this.
 *
 * @param {ArrayBuffer} data
 * @param {bool} [includeHeaderAsData]
 * @returns {{
 *   image: TypedArray,
 *   headerDict: Object.<any>
 * }}
 */
function readMrc(data, includeHeaderAsData = true) {
    let headerbytes = data.slice(0, 1024)
    let headerdict = parseHeader(headerbytes)

    let dt = headerdict["dtype"]
    let swapped = headerdict["swapped"]

    const x = headerdict["nx"]
    const y = headerdict["ny"]
    const z = headerdict["nz"]

    const totalHeaderSize = 1024 + Number(headerdict["nsymbt"])
    const actualData = data.slice((includeHeaderAsData ? 0 : 1024) + totalHeaderSize)

    const image = bytesToData(actualData, dt, swapped)

    // TODO: validate that the above line is equivalent to the Python code below
    
    // if z > 1:
    //     image = numpy.reshape(numpy.fromfile(f, dt, y * x * z), [z, y, x]).astype(
    //         numpy.float32
    //     )
    // else:
    //     image = numpy.reshape(numpy.fromfile(f, dt, y * x), [y, x]).astype(
    //         numpy.float32
    //     )

    // console.log(filename, dt, x, y, z, int(headerdict['nsymbt']))

    return { image, headerdict }
}

/**
 * Convert the integer data of `bytes` to a `dataType` array with single value. 
 * Note, the `dataType` should be some kind of TypedArray.
 * @param {Uint8Array} bytes
 * @param {TypedArray} dataType
 */
function bytesToData(bytes, dataType, swap = false) {

	if (swap !== true) {
		return new dataType(bytes.buffer);
	}

	// NOTE: this function only works on 4-byte buffers
	if (bytes.length != 4) {
		throw new Error(`reverse() won't change byte-order for buffers whose length isn't 4 (len=${bytes.length})`);
	}

	return new dataType(bytes.reverse().buffer);
}

/**
 * Retrieve the bytes from a string using the .charCodeAt() function.
 * FOR TESTING ONLY.
 * @param {string} str
 */
function getStringBytes(str) {
    return new Uint8Array(str.split("").map((char) => char.charCodeAt()))
}

function unitTest() {
    let allTestsPassed = true;
    function assert(name, expected, actual) {
        if (expected != actual) {
            console.error(`Test failed! (${name})`, {
                "expected": expected,
                "actual": actual
            })
            allTestsPassed = false;
        }
    }
    assert("bytes to data", bytesToData(getStringBytes("asdf"), Uint32Array)[0], 1717859169)
    assert("bytes to data", bytesToData(getStringBytes("asdf"), Uint32Array, true)[0], 1634952294)

    if (allTestsPassed) console.log("Tests passed successfully.")
}

export { readMrc }
