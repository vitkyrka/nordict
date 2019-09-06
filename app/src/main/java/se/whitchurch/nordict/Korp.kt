package se.whitchurch.nordict

import android.util.Log
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.*

private const val API_URL = "https://ws.spraakbanken.gu.se/"

// This is almost all the corpora classified under Akademiska texter, Skönlitteratur,
// Tidningstexter, Tidskrifter and Myndighetstexter in the Korp web interface.  Shortening
// the list greatly improves the response time from Korp, but we of course don't
// want to miss out on sentences for rare words.
private const val CORPORA = "sweachum,sweacsam,aspacsv,romi,romii,rom99,storsuc," +
        "romg,gp1994,gp2001,gp2002,gp2003,gp2004,gp2005,gp2006," +
        "gp2007,gp2008,gp2009,gp2010,gp2011,gp2012,gp2013,gp2d," +
        "press95,press96,press97,press98,webbnyheter2001,webbnyheter2002," +
        "webbnyheter2003,webbnyheter2004,webbnyheter2005,webbnyheter2006," +
        "webbnyheter2007,webbnyheter2008,webbnyheter2009,webbnyheter2010," +
        "webbnyheter2011,webbnyheter2012,webbnyheter2013,attasidor," +
        "dn1987,fof,klarsprak,sou,sfs"

class Korp(client: OkHttpClient) {
    private val service: KorpService

    init {
        val retrofit = Retrofit.Builder()
                .client(client)
                .baseUrl(API_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

        service = retrofit.create(KorpService::class.java)
    }

    private fun tokenJoin(kwic: QueryResponse.Kwic): String {
        val sentence = StringBuilder()
        var delimiter = ""
        var previous = ""
        var quotes = 0

        for (i in kwic.tokens!!.indices) {
            val word = kwic.tokens[i].toString()

            if (word == "\"") {
                quotes++
            }

            if (word != ","
                    && word != "."
                    && word != ":"
                    && previous != "("
                    && word != ")"
                    && (previous != "\"" || quotes % 2 == 0)
                    && (word != "\"" || quotes % 2 != 0)) {
                sentence.append(delimiter)
            }

            if (i >= kwic.match!!.start && i < kwic.match.end) {
                sentence.append("<strong>")
                sentence.append(word)
                sentence.append("</strong>")
            } else {
                sentence.append(word)
            }

            previous = word
            delimiter = " "
        }

        return sentence.toString()
    }

    private fun parseJson(p: QueryResponse): ArrayList<String> {
        val sentences = ArrayList<String>()

        if (p.kwic == null) {
            return sentences
        }

        for (kwic in p.kwic) {
            sentences.add(tokenJoin(kwic))
        }

        return sentences
    }

    inner class QueryResponse {
        internal val kwic: ArrayList<Kwic>? = null
        internal val hits: Int = 0

        internal inner class Kwic {
            internal val tokens: ArrayList<Token>? = null
            internal val match: Match? = null

            internal inner class Token {
                private val word: String? = null

                override fun toString(): String {
                    return word!!
                }
            }

            internal inner class Match {
                internal val start: Int = 0
                internal val end: Int = 0
            }
        }
    }

    inner class AutoCompleteResponse {
        private val hits: Hits? = null

        private inner class Hits {
            private val hits: ArrayList<Hit>? = null
            private val total: Int = 0

            private inner class Hit {
                @SerializedName("_source")
                private val source: Source? = null

                private inner class Source {
                    @SerializedName("FormRepresentations")
                    private val formRepresentations: ArrayList<FormRepresentations>? = null

                    private inner class FormRepresentations {
                        internal var lemgram: String? = null
                    }
                }
            }
        }
    }

    interface KorpService {
        @GET("/ws/korp/v7/query?defaultcontext=1+sentence")
        fun query(
                @Query("corpus") corpus: String,
                @Query("cqp") cqp: String,
                @Query("sort") sort: String,
                @Query("start") start: Int?,
                @Query("end") end: Int?
        ): Call<QueryResponse>

        @GET("/ws/karp/v5/autocomplete?resource=saldom")
        fun autoComplete(
                @Query("q") query: String
        ): Call<AutoCompleteResponse>
    }

    private inner class KorpCallback(val word: String, val pos: Pos, val callback: (ArrayList<String>) -> Unit) : Callback<QueryResponse> {
        override fun onResponse(call: Call<QueryResponse>,
                                response: Response<QueryResponse>) {
            if (!response.isSuccessful) {
                Log.i("Blah", response.toString())
                return
            }

            val queryResponse = response.body()
            if (queryResponse.hits == 0 && pos != Pos.UNKNOWN) {
                getSentences(word, Pos.UNKNOWN, callback)
                return
            }

            callback(parseJson(queryResponse))
        }

        override fun onFailure(call: Call<QueryResponse>, t: Throwable) {
            Log.e("Blah", t.message)
            callback(ArrayList())
        }
    }

    internal fun getSentences(word: String, pos: Pos, callback: (ArrayList<String>) -> Unit) {
        val cqp: String

        if (pos == Pos.UNKNOWN) {
            cqp = String.format("\"%s\"", word.replace(" ", "\" \""))
        } else {
            // https://spraakbanken.gu.se/swe/forskning/saldo/taggm%C3%A4ngd
            val korpPos = when (pos) {
                Pos.ADJECTIVE -> "av.*"
                Pos.ADVERB -> "ab.*"
                Pos.INTERJECTION -> "in.*"
                Pos.NOUN -> "n.*"
                Pos.PREPOSITION -> "pp.*"
                Pos.VERB -> "vb.*"
                Pos.CONJUNCTION -> "kn.*"
                Pos.PRONOUN -> "pn.*"
                Pos.UNKNOWN -> ".*"
            }

            cqp = String.format("[lex contains \"%s\\.\\.%s\\.1\"]", word.replace(' ', '_'), korpPos)
        }

        val call = service.query(CORPORA, cqp, "random", 0, 30)
        call.enqueue(KorpCallback(word, pos, callback))
    }
}