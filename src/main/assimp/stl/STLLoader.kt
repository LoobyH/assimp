import cmp
import main.assimp.MAXLEN
import main.d
import main.glm
import main.i
import main.vec._4.Vec4d
import skipSpaces
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.util.*

/**
 * Created by elect on 13/11/2016.
 */

class STLImporter : BaseImporter() {

    companion object {

        val desc = AiImporterDesc(
                "Stereolithography (STL) Importer",
                "",
                "",
                "",
                AiImporterFlags.SupportTextFlavour or AiImporterFlags.SupportBinaryFlavour,
                0,
                0,
                0,
                0,
                "stl")


        // A valid binary STL buffer should consist of the following elements, in order:
        // 1) 80 byte header
        // 2) 4 byte face count
        // 3) 50 bytes per face
        fun isBinarySTL(buffer: ByteBuffer, fileSize: Int): Boolean {
            if (fileSize < 84) {
                return false
            }

            val faceCount = buffer.getInt(80)
            val expectedBinaryFileSize = faceCount * 50 + 84

            return expectedBinaryFileSize == fileSize
        }

        // An ascii STL buffer will begin with "solid NAME", where NAME is optional.
        // Note: The "solid NAME" check is necessary, but not sufficient, to determine
        // if the buffer is ASCII; a binary header could also begin with "solid NAME".
        fun isAsciiSTL(buffer: ByteBuffer, fileSize: Int): Boolean {

            if (isBinarySTL(buffer, fileSize)) return false

            if (!buffer.skipSpaces()) return false

            if (buffer.position() + 5 >= fileSize) return false

            return buffer.cmp("solid").let {
                // A lot of importers are write solid even if the file is binary. So we have to check for ASCII-characters.
                if (fileSize >= 500)
                    for (i in 0..500)
                        if (buffer.get() > 127)
                            return false
                return true
            }
        }
    }

    /** Buffer to hold the loaded file */
    protected lateinit var mBuffer: ByteBuffer

    /** Size of the file, in bytes */
    protected var fileSize = 0

    /** Output scene */
    protected lateinit var pScene: AiScene

    /** Default vertex color */
    protected var clrColorDefault = AiColor4D()

    override fun canRead(pFile: String, checkSig: Boolean): Boolean {

        val extension = pFile.substring(pFile.lastIndexOf('.') + 1)

        if (extension == "stl") {
            return true
        }
//      TODO
//        else if (!extension.isEmpty() || checkSig) {
//            if (!pIOHandler) {
//                return true;
//            }
//            const char * tokens [] = { "STL", "solid" };
//            return SearchFileHeaderForToken(pIOHandler, pFile, tokens, 2);
//        }

        return false
    }

    // ------------------------------------------------------------------------------------------------
    // Imports the given file into the given scene structure.
    override fun internReadFile(pFile: String, pScene: AiScene) {

        val file = File(pFile)

        // Check whether we can read from the file
        if (!file.canRead()) throw FileSystemException("Failed to open STL file $pFile.")

        fileSize = file.length().i

        // allocate storage and copy the contents of the file to a memory buffer
        val fileChannel = RandomAccessFile(file, "r").channel
        val mBuffer2 = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())

        this.pScene = pScene
        this.mBuffer = mBuffer2

        // the default vertex color is light gray.
        clrColorDefault.to(0.6f)

        // allocate a single node
        pScene.mRootNode = AiNode()

        var bMatClr = false

        if (isBinarySTL(mBuffer, fileSize)) {
//            bMatClr = LoadBinaryFile()
        } else if (isAsciiSTL(mBuffer, fileSize))
            loadASCIIFile()
        else
            throw Error("Failed to determine STL storage representation for $pFile.")

        // add all created meshes to the single node
        pScene.mRootNode!!.mNumMeshes = pScene.mNumMeshes
        pScene.mRootNode!!.mMeshes = IntArray(pScene.mNumMeshes)
        for(i in 0..pScene.mNumMeshes)
            pScene.mRootNode!!.mMeshes!![i] = i

        // create a single default material, using a light gray diffuse color for consistency with
        // other geometric types (e.g., PLY).
    }

    // ------------------------------------------------------------------------------------------------
    // Read a binary STL file
    fun loadBinaryFile(): Boolean {

        // allocate one mesh
        pScene.mNumMeshes = 1
        pScene.mMeshes = mutableListOf(AiMesh())
        val pMesh = pScene!!.mMeshes!![0]
        pMesh.mMaterialIndex = 0

        // skip the first 80 bytes
        if (fileSize < 84) throw Error("STL: file is too small for the header")

        var bIsMaterialise = false

        // search for an occurrence of "COLOR=" in the header
        var sz2 = 0
        while (sz2 < 80) {

            if (mBuffer.getChar(sz2++) == 'C' && mBuffer.getChar(sz2++) == 'O' && mBuffer.getChar(sz2++) == 'L' &&
                    mBuffer.getChar(sz2++) == 'O' && mBuffer.getChar(sz2++) == 'R' && mBuffer.getChar(sz2++) == '=') {

                // read the default vertex color for facets
                bIsMaterialise = true
                val invByte = 1f / 255f
                clrColorDefault.r = (mBuffer.getFloat(sz2++) * invByte).d
                clrColorDefault.g = (mBuffer.getFloat(sz2++) * invByte).d
                clrColorDefault.b = (mBuffer.getFloat(sz2++) * invByte).d
                clrColorDefault.a = (mBuffer.getFloat(sz2++) * invByte).d
                break
            }
        }
        var sz = 80

        // now read the number of facets
        pScene.mRootNode!!.mName = "<STL_BINARY>"

        pMesh.mNumFaces = mBuffer.getInt(sz)
        sz += 4

        if (fileSize < 84 + pMesh.mNumFaces * 50) throw Error("STL: file is too small to hold all facets")

        if (pMesh.mNumFaces == 0) throw Error("STL: file is empty. There are no facets defined")

        pMesh.mNumVertices = pMesh.mNumFaces * 3

        pMesh.mVertices = ArrayList<AiVector3D>()
        pMesh.mNormals = ArrayList<AiVector3D>()
        var vp = 0
        var vn = 0

        for (i in 0..pMesh.mNumFaces) {

            // NOTE: Blender sometimes writes empty normals ... this is not
            // our fault ... the RemoveInvalidData helper step should fix that
//            pMesh.mNormals!![vn] = AiVector3D(mBuffer, sz)
        }

        return true
    }

    // ------------------------------------------------------------------------------------------------
    // Read an ASCII STL file
    fun loadASCIIFile() {

        val meshes = ArrayList<AiMesh>()
        val positionBuffer = ArrayList<AiVector3D>()
        val normalBuffer = ArrayList<AiVector3D>()

        val bytes = ByteArray(mBuffer.position(0).remaining())
        mBuffer.get(bytes)
        var buffer = String(bytes)

        val pMesh = AiMesh()
        pMesh.mMaterialIndex = 0
        meshes.add(pMesh)

        buffer = buffer.removePrefix("solid")    // skip the "solid"
        buffer = buffer.trim()

        var words = buffer.split("\\s+".toRegex()).toMutableList()

        // setup the name of the node
        if (!buffer[0].isNewLine()) {
            if (words[0].length >= MAXLEN) throw Error("STL: Node name too long")
            pScene.mRootNode!!.mName = words[0]
        } else pScene.mRootNode!!.mName = "<STL_ASCII>"

        var faceVertexCounter = 0
        var i = 0

        while (true) {

            val word = words[i]

            if (i == word.length - 1 && word != "endsolid") {
                System.err.print("STL: unexpected EOF. \'endsolid\' keyword was expected")
                break
            }

            if (word == "facet") {

                if (faceVertexCounter != 3) System.err.print("STL: A new facet begins but the old is not yet complete")

                faceVertexCounter = 0
                val vn = AiVector3D()
                normalBuffer.add(vn)

                if (words[i + 1] != "normal") System.err.print("STL: a facet normal vector was expected but not found")
                else {
                    try {
                        i++
                        vn.x = words[++i].toDouble()
                        vn.y = words[++i].toDouble()
                        vn.z = words[++i].toDouble()
                        normalBuffer.add(vn.copy())
                        normalBuffer.add(vn.copy())
                    } catch (exc: NumberFormatException) {
                        throw Error("STL: unexpected EOF while parsing facet")
                    }
                }
            } else if (word == "vertex") {

                if (faceVertexCounter >= 3) {
                    System.err.print("STL: a facet with more than 3 vertices has been found")
                    i++
                } else {
                    try {
                        val vn = AiVector3D()
                        vn.x = words[++i].toDouble()
                        vn.y = words[++i].toDouble()
                        vn.z = words[++i].toDouble()
                        positionBuffer.add(vn)
                        faceVertexCounter++
                    } catch (exc: NumberFormatException) {
                        throw Error("STL: unexpected EOF while parsing facet")
                    }
                }
            }
            // finished!
            else if (word == "endsolid") break

            i++
        }

        if (positionBuffer.isEmpty()) {
            pMesh.mNumFaces = 0
            throw Error("STL: ASCII file is empty or invalid; no data loaded")
        }
        if (positionBuffer.size % 3 != 0) {
            pMesh.mNumFaces = 0
            throw Error("STL: Invalid number of vertices")
        }
        if (normalBuffer.size != positionBuffer.size) {
            pMesh.mNumFaces = 0
            throw Error("Normal buffer size does not match position buffer size")
        }
        pMesh.mNumFaces = positionBuffer.size / 3
        pMesh.mNumVertices = positionBuffer.size
        pMesh.mVertices = positionBuffer.toList()
        positionBuffer.clear()
        pMesh.mNormals = normalBuffer.toList()
        normalBuffer.clear()

        pScene.mRootNode!!.mName = words[0]

        // now copy faces
        addFacesToMesh(pMesh)

        // now add the loaded meshes
        pScene.mNumMeshes = meshes.size
        pScene.mMeshes = ArrayList<AiMesh>(pScene.mNumMeshes)
        for (i in 0..meshes.size) pScene.mMeshes!![i] = meshes[i]
    }

    fun addFacesToMesh(pMesh: AiMesh) {
        var p = 0
        pMesh.mFaces = ArrayList<AiFace>(pMesh.mNumFaces)
        for (face in pMesh.mFaces!!) {
            face.mNumIndices = 3
            face.mIndices = IntArray(face.mNumIndices)
            for (o in 0..2) face.mIndices!![o] = p++
        }
    }
}