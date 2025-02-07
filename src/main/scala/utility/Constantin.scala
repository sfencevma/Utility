/***************************************************************************************
  * Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
  * Copyright (c) 2020-2021 Peng Cheng Laboratory
  *
  * XiangShan is licensed under Mulan PSL v2.
  * You can use this software according to the terms and conditions of the Mulan PSL v2.
  * You may obtain a copy of Mulan PSL v2 at:
  *          http://license.coscl.org.cn/MulanPSL2
  *
  * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
  * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
  * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
  *
  * See the Mulan PSL v2 for more details.
  ***************************************************************************************/

package utility

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.experimental.StringParam
import chisel3.util._
import freechips.rocketchip.util.ElaborationArtefacts

import scala.reflect._

trait ConstantinParams {
  def UIntWidth = 64
  def getdpicFunc(constName: String) = {
    s"${constName}_constantin_read"
  }
  def getModuleName(constName: String) = {
    s"${constName}_constantinReader"
  }
}

private class SignalReadHelper(constName: String) extends BlackBox with HasBlackBoxInline with ConstantinParams {
  val io = IO(new Bundle{
    //val clock = Input(Clock())
    //val reset = Input(Reset())
    val value = Output(UInt(UIntWidth.W))
  })

  val moduleName = getModuleName(constName)
  val dpicFunc = getdpicFunc(constName)

  val verilog =
    s"""
       |import "DPI-C" function longint $dpicFunc();
       |
       |module $moduleName(
       |  output [$UIntWidth - 1:0] value
       |);
       |
       |  assign value = $dpicFunc();
       |endmodule
       |""".stripMargin
  setInline(s"$moduleName.v", verilog)
  override def desiredName: String = moduleName
}

class MuxModule[A <: Record](gen: A, n: Int) extends Module {
  val io = IO(new Bundle{
    val in = Flipped(Vec(n, gen))
    val sel = Input(UInt(log2Ceil(n).W))
    val out = gen
  })
  io.in.foreach(t => t <> DontCare)
  io.out <> io.in(0)
  io.in.zipWithIndex.map{case (t, i) => when (io.sel === i.U) {io.out <> t}}
}

/*
* File format: constName expected_runtimevalue (unsigned DEC)
* */

object Constantin extends ConstantinParams {
  private val recordMap = scala.collection.mutable.Map[String, UInt]()
  def createRecord(constName: String): UInt = {
    val t = WireInit(0.U.asTypeOf(UInt(UIntWidth.W)))
    if (recordMap.contains(constName)) {
      recordMap.getOrElse(constName, 0.U)
    } else {
      recordMap += (constName -> t)
      val recordModule = Module(new SignalReadHelper(constName))
      //recordModule.io.clock := Clock()
      //recordModule.io.reset := Reset()
      t := recordModule.io.value

      // print record info
      println(s"Constantin ${constName}")

      t
    }

  }

  def getCHeader: String = {
    s"""
       |#ifndef CONSTANTIN_H
       |#define CONSTANTIN_H
       |
       |#endif // CONSTANTIN_H
       |""".stripMargin
  }

  def getPreProcessCpp: String = {
    s"""
       |#include <iostream>
       |#include <fstream>
       |#include <map>
       |#include <string>
       |#include <string>
       |#include <cstdlib>
       |#include <stdint.h>
       |using namespace std;
       |
       |fstream cf;
       |map<string, uint64_t> constantinMap;
       |void constantinLoad() {
       |  uint64_t num;
       |  string tmp;
       |  string noop_home = getenv("NOOP_HOME");
       |  tmp = noop_home + "/build/constantin.txt";
       |  cf.open(tmp.c_str(), ios::in);
       |  while (!cf.eof()) {
       |    cf>>tmp>>num;
       |    constantinMap.insert(make_pair(tmp, num));
       |  }
       |  cf.close();
       |
       |}
       |""".stripMargin + recordMap.map({a => getCpp(a._1)}).foldLeft("")(_ + _)
  }
  def getCpp(constName: String): String = {
    s"""
       |#include <map>
       |#include <string>
       |#include <stdint.h>
       |using namespace std;
       |extern map<string, uint64_t> constantinMap;
       |extern "C" uint64_t ${getdpicFunc(constName)}() {
       |  return constantinMap["${constName}"];
       |}
       |""".stripMargin
  }

  def addToElaborationArtefacts = {
    ElaborationArtefacts.add("hxx", getCHeader)
    ElaborationArtefacts.add("cxx", getPreProcessCpp)
    // recordMap.map({a => ElaborationArtefacts.add("cpp", getCpp(a._1))})
  }

}