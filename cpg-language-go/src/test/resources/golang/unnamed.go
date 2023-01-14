package main

import "context"

type MyStructU struct{}

func (MyStructU) MyFunc() int {
	return 1
}

func MyGlobalFunc(_ context.Context) int {
	return 2
}
