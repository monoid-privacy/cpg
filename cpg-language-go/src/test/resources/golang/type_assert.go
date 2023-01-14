package main

import "fmt"

type MyStructTA struct{}
type MyInterface interface {
	MyFunc()
}

func (MyStructTA) MyFunc() {}

func main() {
	var f MyInterface = MyStructTA{}
	var s = f.(MyStructTA)

	fmt.Printf("%+v", s)
}
