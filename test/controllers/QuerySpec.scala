package test.controllers

import play.api.libs.json._
import play.api.test.Helpers._
import play.api.test.{FakeApplication, FakeRequest}

import scala.concurrent.Await

class QuerySpec extends SpecCommon {
  init()

  object QueryBuilder {

    import org.json4s.native.Serialization

    import scala.language.dynamics

    def aa[T](args: T*) = List(a(args: _ *))

    def a[T](args: T*) = args.toList

    object m extends Dynamic {
      def applyDynamicNamed(name: String)(args: (String, Any)*): Map[String, Any] = args.toMap
    }

    implicit class anyMapOps(map: Map[String, Any]) {
      def toJson: JsValue = {
        val js = Serialization.write(map)(org.json4s.DefaultFormats)
        Json.parse(js)
      }
    }
  }

  "query test" should {
    running(FakeApplication()) {
      // insert bulk and wait ..
      val bulkEdges: String = Seq(
        Seq(1000, "insert", "e", "0", "1", testLabelName, "{\"weight\": 10, \"is_hidden\": true}").mkString("\t"),
        Seq(2000, "insert", "e", "0", "2", testLabelName, "{\"weight\": 20, \"is_hidden\": false}").mkString("\t"),
        Seq(3000, "insert", "e", "2", "0", testLabelName, "{\"weight\": 30}").mkString("\t"),
        Seq(4000, "insert", "e", "2", "1", testLabelName, "{\"weight\": 40}").mkString("\t")
      ).mkString("\n")
      val req = FakeRequest(POST, "/graphs/edges/bulk").withBody(bulkEdges)
      Await.result(route(req).get, HTTP_REQ_WAITING_TIME)
      Thread.sleep(asyncFlushInterval)
    }

    def query(id: Int) = Json.parse( s"""
        { "srcVertices": [
          { "serviceName": "${testServiceName}",
            "columnName": "${testColumnName}",
            "id": ${id}
           }],
          "steps": [
          [ {
              "label": "${testLabelName}",
              "direction": "out",
              "offset": 0,
              "limit": 2
            },
            {
              "label": "${testLabelName}",
              "direction": "in",
              "offset": 0,
              "limit": 2,
              "exclude": true
            }
          ]]
        }""")

    def queryTransform(id: Int, transforms: String) = Json.parse( s"""
        { "srcVertices": [
          { "serviceName": "${testServiceName}",
            "columnName": "${testColumnName}",
            "id": ${id}
           }],
          "steps": [
          [ {
              "label": "${testLabelName}",
              "direction": "out",
              "offset": 0,
              "transform": $transforms
            }
          ]]
        }""")

    def queryWhere(id: Int, where: String) = Json.parse( s"""
        { "srcVertices": [
          { "serviceName": "${testServiceName}",
            "columnName": "${testColumnName}",
            "id": ${id}
           }],
          "steps": [
          [ {
              "label": "${testLabelName}",
              "direction": "out",
              "offset": 0,
              "limit": 100,
              "where": "${where}"
            }
          ]]
        }""")

    def getEdges(queryJson: JsValue): JsValue = {
      val ret = route(FakeRequest(POST, "/graphs/getEdges").withJsonBody(queryJson)).get
      contentAsJson(ret)
    }

    "get edge with where condition" in {
      running(FakeApplication()) {
        var result = getEdges(queryWhere(0, "is_hidden=false and _from in (-1, 0)"))
        (result \ "results").as[List[JsValue]].size must equalTo(1)

        result = getEdges(queryWhere(0, "is_hidden=true and _to in (1)"))
        (result \ "results").as[List[JsValue]].size must equalTo(1)

        result = getEdges(queryWhere(0, "_from=0"))
        (result \ "results").as[List[JsValue]].size must equalTo(2)

        result = getEdges(queryWhere(2, "_from=2 or weight in (-1)"))
        (result \ "results").as[List[JsValue]].size must equalTo(2)

        result = getEdges(queryWhere(2, "_from=2 and weight in (30, 40)"))
        (result \ "results").as[List[JsValue]].size must equalTo(2)
      }
    }

    "get edge exclude" in {
      running(FakeApplication()) {
        val result = getEdges(query(0))
        (result \ "results").as[List[JsValue]].size must equalTo(1)
      }
    }

    "edge transform " in {
      running(FakeApplication()) {
        var result = getEdges(queryTransform(0, "[[\"_to\"]]"))
        (result \ "results").as[List[JsValue]].size must equalTo(2)

        result = getEdges(queryTransform(0, "[[\"weight\"]]"))
        (result \ "results").as[List[JsValue]].size must equalTo(4)
      }
    }

    def queryDuration(ids: Seq[Int], from: Int, to: Int) = {
      import QueryBuilder._
      val js = m(
        srcVertices = a(
          m(serviceName = testServiceName, columnName = testColumnName, ids = ids)),
        steps = a(m(step = a(
          m(label = testLabelName, direction = "out", offset = 0, limit = 100, duration = m(from = from, to = to)))))
      ).toJson
      js
    }

    "duration" in {
      running(FakeApplication()) {
         // get all
        var result = getEdges(queryDuration(Seq(0, 2), from = 0, to = 5000))
        (result \ "results").as[List[JsValue]].size must equalTo(4)

        // inclusive, exclusive
        result = getEdges(queryDuration(Seq(0, 2), from = 1000, to = 4000))
        (result \ "results").as[List[JsValue]].size must equalTo(3)

        result = getEdges(queryDuration(Seq(0, 2), from = 1000, to = 2000))
        (result \ "results").as[List[JsValue]].size must equalTo(1)

        val bulkEdges: String = Seq(
          Seq(1001, "insert", "e", "0", "1", testLabelName, "{\"weight\": 10, \"is_hidden\": true}").mkString("\t"),
          Seq(2002, "insert", "e", "0", "2", testLabelName, "{\"weight\": 20, \"is_hidden\": false}").mkString("\t"),
          Seq(3003, "insert", "e", "2", "0", testLabelName, "{\"weight\": 30}").mkString("\t"),
          Seq(4004, "insert", "e", "2", "1", testLabelName, "{\"weight\": 40}").mkString("\t")
        ).mkString("\n")

        val req = FakeRequest(POST, "/graphs/edges/bulk").withBody(bulkEdges)
        Await.result(route(req).get, HTTP_REQ_WAITING_TIME)
        Thread.sleep(asyncFlushInterval)

        // duration test after udpate
        // get all
        result = getEdges(queryDuration(Seq(0, 2), from = 0, to = 5000))
        (result \ "results").as[List[JsValue]].size must equalTo(4)

        // inclusive, exclusive
        result = getEdges(queryDuration(Seq(0, 2), from = 1000, to = 4000))
        (result \ "results").as[List[JsValue]].size must equalTo(3)

        result = getEdges(queryDuration(Seq(0, 2), from = 1000, to = 2000))
        (result \ "results").as[List[JsValue]].size must equalTo(1)
        true
      }
    }
  }
}
